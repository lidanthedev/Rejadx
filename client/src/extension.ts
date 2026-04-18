import * as vscode from 'vscode';
import * as fs from 'fs/promises';
import { startLanguageClient, stopLanguageClient, stopLanguageClientAndDisposeOutput, getClient, getReJadxSettings, validateConfiguredJadxJarPath } from './languageClient';
import { DashboardProvider, TelemetryUpdate } from './views/DashboardProvider';
import { ClassTreeProvider, PackageNode } from './views/ClassTreeProvider';

interface SourceReadyParams {
  uri: string;
  content: string;
  languageId?: string;
}

interface InflightRequest {
  token: symbol;
  promise: Thenable<string>;
}

interface LspWorkspaceEdit {
  changes?: Record<string, unknown[]>;
}

interface ExportMappingsResult {
  mapping: string;
}

interface SearchResult {
  uri: string;
  line: number;
  character: number;
  length: number;
}

interface ServerSettingsResult {
  applied: boolean;
}

interface CommentLookupResult {
  exists: boolean;
  comment: string;
  style: string;
}

interface ResetCodeCacheResult {
  reset: boolean;
  loaded?: boolean;
  classCount?: number;
  cacheDir?: string;
  error?: string;
}

let persistOnDeactivate: (() => Promise<void>) | undefined;
let closeTabsOnDeactivate: (() => Promise<void>) | undefined;
const RECENT_PROJECTS_KEY = 'recentProjects';
const MAX_RECENT_PROJECTS = 5;
const PROJECT_OPEN_TABS_KEY = 'projectOpenTabs';
const MAX_PROJECT_TABS = 30;
const MISSING_JADX_PROMPTED_KEY = 'missingJadxJarPrompted';
const JADX_DOWNLOAD_URL = 'https://github.com/skylot/jadx/releases';

function languageIdForJadxUri(uri: vscode.Uri): string {
  const q = uri.query || '';
  if (q.includes('type=smali')) {
    return 'smali';
  }
  if (uri.path.startsWith('/resources/')) {
    const lower = uri.path.toLowerCase();
    if (lower.endsWith('.xml')) {
      return 'xml';
    }
    if (lower.endsWith('.json')) {
      return 'json';
    }
    if (lower.endsWith('.html') || lower.endsWith('.htm')) {
      return 'html';
    }
    return 'plaintext';
  }
  return 'java';
}

async function ensureJadxDocumentLanguage(doc: vscode.TextDocument): Promise<void> {
  if (doc.uri.scheme !== 'jadx') {
    return;
  }
  const target = languageIdForJadxUri(doc.uri);
  if (doc.languageId === target) {
    return;
  }
  try {
    await vscode.languages.setTextDocumentLanguage(doc, target);
  } catch {
    // Ignore language mode failures for virtual documents.
  }
}

class JadxContentProvider implements vscode.TextDocumentContentProvider {
  private readonly _cache = new Map<string, string>();
  private readonly _inflight = new Map<string, InflightRequest>();
  readonly onDidChangeEmitter = new vscode.EventEmitter<vscode.Uri>();
  readonly onDidChange = this.onDidChangeEmitter.event;

  update(uriStr: string, content: string): void {
    const normalized = vscode.Uri.parse(uriStr).toString();
    this._cache.set(normalized, content);
    this.onDidChangeEmitter.fire(vscode.Uri.parse(normalized));
  }

  invalidate(uriStr: string): void {
    const normalized = vscode.Uri.parse(uriStr).toString();
    this._cache.delete(normalized);
    this._inflight.delete(normalized);
    this.onDidChangeEmitter.fire(vscode.Uri.parse(normalized));
  }

  provideTextDocumentContent(uri: vscode.Uri): string | Thenable<string> {
    const key = uri.toString();
    const cached = this._cache.get(key);
    if (cached !== undefined) {
      return cached;
    }

    const lc = getClient();
    if (!lc) {
      return '// Loading...';
    }

    const pending = this._inflight.get(key);
    if (pending) {
      return pending.promise;
    }

    const token = Symbol(key);
    const request = lc.sendRequest('workspace/executeCommand', {
      command: 'rejadx.getSource',
      arguments: [key]
    }).then((res) => {
      const content = typeof res === 'string'
        ? res
        : (res as { content?: string } | undefined)?.content ?? '// Empty source';
      if (this._inflight.get(key)?.token === token) {
        this.update(key, content);
      }
      return content;
    }).catch((err) => {
      const message = `// Failed to load source: ${err instanceof Error ? err.message : String(err)}`;
      if (this._inflight.get(key)?.token === token) {
        this.update(key, message);
      }
      return message;
    }).finally(() => {
      if (this._inflight.get(key)?.token === token) {
        this._inflight.delete(key);
      }
    });

    this._inflight.set(key, { token, promise: request });
    return request;
  }
}

export async function activate(context: vscode.ExtensionContext): Promise<void> {
  let clientHooksInstalled = false;
  let lastLoadedApkPath: string | undefined;
  let dashboardInitialized = false;
  let suppressTabPersistence = false;
  let ensureClientStarted: () => Promise<NonNullable<ReturnType<typeof getClient>>>;
  let initDashboardState: () => Promise<void>;

  const contentProvider = new JadxContentProvider();
  // Register BEFORE starting LSP to avoid a race on the first sourceReady notification.
  context.subscriptions.push(
    vscode.workspace.registerTextDocumentContentProvider('jadx', contentProvider)
  );

  const dashboardProvider = new DashboardProvider(context.extensionUri);
  context.subscriptions.push(
    vscode.window.registerWebviewViewProvider('rejadx.dashboard', dashboardProvider)
  );

  const classTreeProvider = new ClassTreeProvider();
  context.subscriptions.push(
    vscode.window.createTreeView('rejadx.classes', {
      treeDataProvider: classTreeProvider,
      showCollapseAll: true
    })
  );

  const updateJadxEnvironmentWarning = (): void => {
    const validation = validateConfiguredJadxJarPath();
    dashboardProvider.setEnvironmentWarning(validation.ok
      ? ''
      : (validation.reason ?? 'JADX jar is not configured.'));
  };

  const promptForJadxJarOnFirstRunIfNeeded = async (): Promise<void> => {
    const validation = validateConfiguredJadxJarPath();
    updateJadxEnvironmentWarning();
    if (validation.ok) {
      return;
    }

    const alreadyPrompted = context.globalState.get<boolean>(MISSING_JADX_PROMPTED_KEY, false);
    if (alreadyPrompted) {
      return;
    }

    await context.globalState.update(MISSING_JADX_PROMPTED_KEY, true);

    const pick = await vscode.window.showWarningMessage(
      'ReJadx needs a local JADX JAR to run. Select your JADX jar or download it.',
      'Select JAR',
      'Download JADX'
    );

    if (pick === 'Select JAR') {
      await vscode.commands.executeCommand('rejadx.selectJadxJar');
      return;
    }
    if (pick === 'Download JADX') {
      await vscode.env.openExternal(vscode.Uri.parse(JADX_DOWNLOAD_URL));
    }
  };

  context.subscriptions.push(vscode.workspace.onDidChangeConfiguration((e) => {
    if (e.affectsConfiguration('rejadx.jadxJarPath')) {
      updateJadxEnvironmentWarning();
    }
  }));

  context.subscriptions.push(vscode.commands.registerCommand('rejadx.selectJadxJar', async () => {
    const pick = await vscode.window.showOpenDialog({
      canSelectFiles: true,
      canSelectFolders: false,
      canSelectMany: false,
      title: 'Select JADX jar',
      filters: { 'JAR Files': ['jar'], 'All Files': ['*'] }
    });
    if (!pick || !pick[0]) {
      return;
    }

    const cfg = vscode.workspace.getConfiguration('rejadx');
    await cfg.update('jadxJarPath', pick[0].fsPath, vscode.ConfigurationTarget.Global);
    updateJadxEnvironmentWarning();
    vscode.window.showInformationMessage(`ReJadx: JADX jar path set to ${pick[0].fsPath}`);
  }));

  const getRecentProjects = (): string[] => {
    const saved = context.workspaceState.get<string[]>(RECENT_PROJECTS_KEY);
    return Array.isArray(saved) ? saved.filter(Boolean) : [];
  };

  const getProjectOpenTabsMap = (): Record<string, string[]> => {
    const saved = context.workspaceState.get<Record<string, string[]>>(PROJECT_OPEN_TABS_KEY);
    return saved && typeof saved === 'object' ? saved : {};
  };

  const saveProjectOpenTabsMap = async (value: Record<string, string[]>): Promise<void> => {
    await context.workspaceState.update(PROJECT_OPEN_TABS_KEY, value);
  };

  const getOpenJadxUris = (): string[] => {
    const uris: string[] = [];
    for (const group of vscode.window.tabGroups.all) {
      for (const tab of group.tabs) {
        const input = tab.input as unknown as { uri?: vscode.Uri };
        if (input?.uri?.scheme === 'jadx') {
          uris.push(input.uri.toString());
        }
      }
    }
    return [...new Set(uris)];
  };

  const getOpenJadxTabs = (): vscode.Tab[] => {
    const tabs: vscode.Tab[] = [];
    for (const group of vscode.window.tabGroups.all) {
      for (const tab of group.tabs) {
        const input = tab.input as unknown as { uri?: vscode.Uri };
        if (input?.uri?.scheme === 'jadx') {
          tabs.push(tab);
        }
      }
    }
    return tabs;
  };

  const persistCurrentProjectTabs = async (): Promise<void> => {
    if (!lastLoadedApkPath) {
      return;
    }
    const openUris = getOpenJadxUris().slice(0, MAX_PROJECT_TABS);
    const map = getProjectOpenTabsMap();
    map[lastLoadedApkPath] = openUris;
    await saveProjectOpenTabsMap(map);
  };

  persistOnDeactivate = persistCurrentProjectTabs;

  const closeVirtualTabsAfterSave = async (): Promise<void> => {
    const tabs = getOpenJadxTabs();
    if (tabs.length === 0) {
      return;
    }

    // Drop cached virtual content before closing so next open always refetches.
    for (const tab of tabs) {
      const input = tab.input as unknown as { uri?: vscode.Uri };
      if (input?.uri) {
        contentProvider.invalidate(input.uri.toString());
      }
    }

    suppressTabPersistence = true;
    try {
      await vscode.window.tabGroups.close(tabs, true);
    } catch {
      // Best effort.
    } finally {
      suppressTabPersistence = false;
    }
  };

  closeTabsOnDeactivate = closeVirtualTabsAfterSave;

  let persistTabsTimer: NodeJS.Timeout | undefined;
  const schedulePersistTabs = (): void => {
    if (suppressTabPersistence) {
      return;
    }
    if (persistTabsTimer) {
      clearTimeout(persistTabsTimer);
    }
    persistTabsTimer = setTimeout(() => {
      void persistCurrentProjectTabs();
    }, 400);
  };

  context.subscriptions.push(vscode.window.tabGroups.onDidChangeTabs(() => {
    schedulePersistTabs();
  }));

  const restoreProjectTabs = async (apkPath: string): Promise<void> => {
    const map = getProjectOpenTabsMap();
    const saved = Array.isArray(map[apkPath]) ? map[apkPath] : [];
    if (saved.length === 0) {
      return;
    }

    // Give language client/server a moment to fully settle after load/restart.
    await new Promise(resolve => setTimeout(resolve, 1000));

    const alreadyOpen = new Set(vscode.workspace.textDocuments.map(d => d.uri.toString()));
    for (const raw of saved) {
      if (!raw || alreadyOpen.has(raw)) {
        continue;
      }
      try {
        await vscode.commands.executeCommand('vscode.open', vscode.Uri.parse(raw), {
          preview: false,
          preserveFocus: true
        });
        await new Promise(resolve => setTimeout(resolve, 100));
      } catch {
        // Skip tabs that fail to restore.
      }
    }
  };

  const saveRecentProjects = async (projects: string[]): Promise<void> => {
    await context.workspaceState.update(RECENT_PROJECTS_KEY, projects);
    dashboardProvider.setRecentProjects(projects);
  };

  const invalidateOpenJavaVirtualDocs = (): void => {
    const uris = new Set<string>();
    for (const doc of vscode.workspace.textDocuments) {
      if (doc.uri.scheme !== 'jadx') {
        continue;
      }
      if (languageIdForJadxUri(doc.uri) !== 'java') {
        continue;
      }
      uris.add(doc.uri.toString());
    }
    for (const uri of uris) {
      contentProvider.invalidate(uri);
    }
  };

  const pushRecentProject = async (apkPath: string): Promise<void> => {
    const current = getRecentProjects().filter(p => p !== apkPath);
    current.unshift(apkPath);
    await saveRecentProjects(current.slice(0, MAX_RECENT_PROJECTS));
  };

  initDashboardState = async (): Promise<void> => {
    dashboardProvider.setRecentProjects(getRecentProjects());
    if (lastLoadedApkPath) {
      dashboardProvider.setApkPath(lastLoadedApkPath);
    }
    updateJadxEnvironmentWarning();
  };

  dashboardProvider.setViewReadyHandler(() => {
    if (dashboardInitialized) {
      void initDashboardState();
      return;
    }
    dashboardInitialized = true;
    void initDashboardState();
  });

  ensureClientStarted = async (): Promise<NonNullable<ReturnType<typeof getClient>>> => {
    const validation = validateConfiguredJadxJarPath();
    if (!validation.ok) {
      updateJadxEnvironmentWarning();
      throw new Error(validation.reason ?? 'JADX jar is not configured');
    }

    if (!getClient()) {
      await startLanguageClient(context);
    }

    const client = getClient();
    if (!client) {
      throw new Error('Language server failed to start');
    }

    if (!clientHooksInstalled) {
      client.onNotification('rejadx/sourceReady', (params: SourceReadyParams) => {
        contentProvider.update(params.uri, params.content);

        const uri = vscode.Uri.parse(params.uri);
        const openDoc = vscode.workspace.textDocuments.find(d => d.uri.toString() === uri.toString());
        if (openDoc) {
          const desired = params.languageId && params.languageId.length > 0
            ? params.languageId
            : 'java';
          if (openDoc.languageId !== desired) {
            void vscode.languages.setTextDocumentLanguage(openDoc, desired).then(() => undefined, () => undefined);
          }
        }
      });

      client.onNotification('rejadx/telemetry', (params: TelemetryUpdate) => {
        dashboardProvider.updateTelemetry(params);
      });

      clientHooksInstalled = true;
    }

    return client;
  };

  void promptForJadxJarOnFirstRunIfNeeded();

  dashboardProvider.setBrowseHandler(async () => {
    const uris = await vscode.window.showOpenDialog({
      canSelectFiles: true,
      canSelectFolders: false,
      canSelectMany: false,
      filters: { 'Android Artifacts': ['apk', 'dex', 'jar', 'aar'], 'All Files': ['*'] },
      title: 'Open APK / DEX file'
    });
    if (uris?.[0]) {
      dashboardProvider.setApkPath(uris[0].fsPath);
    }
  });

  dashboardProvider.setOpenApkHandler(async (apkPath: string) => {
    if (lastLoadedApkPath && lastLoadedApkPath !== apkPath) {
      await persistCurrentProjectTabs();
    }

    dashboardProvider.notifyProjectLoading(apkPath);

    try {
      const lc = await ensureClientStarted();
      const settings = getReJadxSettings();
      await lc.sendRequest('workspace/executeCommand', {
        command: 'rejadx.setSettings',
        arguments: [{
          customArgs: settings.customJadxArgs ?? '',
          enableExternalPlugins: settings.enableExternalPlugins,
          enableCodeCache: settings.enableCodeCache
        }]
      }) as ServerSettingsResult;

      const result = await lc.sendRequest('workspace/executeCommand', {
        command: 'rejadx.loadProject',
        arguments: [apkPath]
      }) as { classCount?: number; loaded: boolean; error?: string };

      if (!result.loaded) {
        const reason = result.error ?? 'Failed to load project';
        dashboardProvider.notifyProjectLoadFailed(reason);
        vscode.window.showErrorMessage(`ReJadx: ${reason}`);
        return;
      }

      lastLoadedApkPath = apkPath;
      await pushRecentProject(apkPath);
      dashboardProvider.notifyProjectLoaded(result.classCount ?? 0);

      const packages = await lc.sendRequest('workspace/executeCommand', {
        command: 'rejadx.getPackages',
        arguments: []
      }) as PackageNode[];
      classTreeProvider.setRoots(packages);

      await restoreProjectTabs(apkPath);
    } catch (err) {
      vscode.window.showErrorMessage(`ReJadx: ${err}`);
    }
  });

  const stopProjectSession = async (disposeOutputChannel: boolean): Promise<void> => {
    await persistCurrentProjectTabs();
    await closeVirtualTabsAfterSave();

    if (disposeOutputChannel) {
      await stopLanguageClientAndDisposeOutput();
    } else {
      await stopLanguageClient();
    }
    clientHooksInstalled = false;
    dashboardProvider.notifyProjectClosed();
    classTreeProvider.setRoots([]);
  };

  dashboardProvider.setRestartProjectHandler(async () => {
    try {
      const targetApk = lastLoadedApkPath;
      await stopProjectSession(false);
      const lc = await ensureClientStarted();

      if (targetApk) {
        dashboardProvider.notifyProjectLoading(targetApk);

        const settings = getReJadxSettings();
        await lc.sendRequest('workspace/executeCommand', {
          command: 'rejadx.setSettings',
          arguments: [{
            customArgs: settings.customJadxArgs ?? '',
            enableExternalPlugins: settings.enableExternalPlugins,
            enableCodeCache: settings.enableCodeCache
          }]
        }) as ServerSettingsResult;

        const result = await lc.sendRequest('workspace/executeCommand', {
          command: 'rejadx.loadProject',
          arguments: [targetApk]
        }) as { classCount?: number; loaded: boolean; error?: string };

        if (!result.loaded) {
          const reason = result.error ?? 'Failed to load project';
          dashboardProvider.notifyProjectLoadFailed(reason);
          vscode.window.showErrorMessage(`ReJadx: ${reason}`);
          return;
        }

        dashboardProvider.notifyProjectLoaded(result.classCount ?? 0);

        const packages = await lc.sendRequest('workspace/executeCommand', {
          command: 'rejadx.getPackages',
          arguments: []
        }) as PackageNode[];
        classTreeProvider.setRoots(packages);

        await restoreProjectTabs(targetApk);
      } else {
        dashboardProvider.notifyProjectClosed();
      }
      vscode.window.showInformationMessage('ReJadx: server restarted.');
    } catch (err) {
      vscode.window.showErrorMessage(`ReJadx: restart failed: ${err}`);
    }
  });

  dashboardProvider.setStopProjectHandler(async () => {
    try {
      await stopProjectSession(true);
      vscode.window.showInformationMessage('ReJadx: server stopped.');
    } catch (err) {
      vscode.window.showErrorMessage(`ReJadx: stop failed: ${err}`);
    }
  });

  context.subscriptions.push(vscode.workspace.onDidOpenTextDocument((doc) => {
    void ensureJadxDocumentLanguage(doc);
  }));
  for (const doc of vscode.workspace.textDocuments) {
    void ensureJadxDocumentLanguage(doc);
  }

  context.subscriptions.push(vscode.commands.registerCommand('jadx.addComment', async () => {
    const editor = vscode.window.activeTextEditor;
    if (!editor) {
      return;
    }

    const uri = editor.document.uri;
    if (uri.scheme !== 'jadx') {
      vscode.window.showWarningMessage('ReJadx: Comments can only be added in jadx:// documents.');
      return;
    }

    const client = getClient();
    const readyClient = client ?? await ensureClientStarted();

    const pos = editor.selection.active;

    let initialComment = '';
    try {
      const lookup = await readyClient.sendRequest('workspace/executeCommand', {
        command: 'rejadx.getComment',
        arguments: [{
          uri: uri.toString(),
          line: pos.line,
          character: pos.character
        }]
      }) as CommentLookupResult;
      if (lookup?.exists) {
        initialComment = lookup.comment ?? '';
      }
    } catch {
      // Ignore prefill lookup errors.
    }

    const comment = await vscode.window.showInputBox({
      prompt: 'Set comment (empty to remove)',
      placeHolder: 'Enter comment text',
      value: initialComment,
      ignoreFocusOut: true
    });
    if (comment === undefined) {
      return;
    }

    try {
      const res = await readyClient.sendRequest('jadx/addComment', {
        uri: uri.toString(),
        line: pos.line,
        character: pos.character,
        comment,
        style: 'LINE'
      }) as { content?: string };

      if (typeof res?.content === 'string' && res.content.length > 0) {
        contentProvider.update(uri.toString(), res.content);
      } else {
        contentProvider.invalidate(uri.toString());
      }

      vscode.window.showInformationMessage(comment.trim().length === 0
        ? 'ReJadx: Comment removed.'
        : 'ReJadx: Comment updated.');
    } catch (err) {
      vscode.window.showErrorMessage(`ReJadx: add comment failed: ${err}`);
    }
  }));

  context.subscriptions.push(vscode.commands.registerCommand('rejadx.renameAtCursor', async () => {
    const editor = vscode.window.activeTextEditor;
    if (!editor || editor.document.uri.scheme !== 'jadx') {
      return;
    }
    await vscode.commands.executeCommand('rejadx.renameAtCursorDirect');
  }));

  context.subscriptions.push(vscode.commands.registerCommand('rejadx.renameAtCursorDirect', async () => {
    const editor = vscode.window.activeTextEditor;
    if (!editor || editor.document.uri.scheme !== 'jadx') {
      return;
    }

    const selectedText = editor.document.getText(editor.selection);
    const initialName = selectedText.length > 0
      ? selectedText
      : (() => {
        const wordRange = editor.document.getWordRangeAtPosition(editor.selection.active);
        return wordRange ? editor.document.getText(wordRange) : '';
      })();

    const newName = await vscode.window.showInputBox({
      prompt: 'Rename symbol (empty to reset)',
      value: initialName,
      ignoreFocusOut: true,
      validateInput: (v) => {
        if (v.length === 0) {
          return null;
        }
        return /^[$_a-zA-Z][$_a-zA-Z0-9]*$/.test(v) ? null : 'Invalid Java identifier';
      }
    });
    if (newName === undefined) {
      return;
    }

    const client = getClient();
    const readyClient = client ?? await ensureClientStarted();

    try {
      const pos = editor.selection.active;
      const edit = await readyClient.sendRequest('textDocument/rename', {
        textDocument: { uri: editor.document.uri.toString() },
        position: { line: pos.line, character: pos.character },
        newName
      }) as LspWorkspaceEdit;

      const uriKey = editor.document.uri.toString();
      const hasChange = (edit?.changes?.[uriKey]?.length ?? 0) > 0;
      if (!hasChange) {
        vscode.window.showWarningMessage('ReJadx: rename edit was not applied.');
        return;
      }

      // Force full refresh of all open Java virtual docs to avoid stale cross-file symbols.
      invalidateOpenJavaVirtualDocs();
    } catch (err) {
      vscode.window.showErrorMessage(`ReJadx: rename failed: ${err}`);
    }
  }));

  context.subscriptions.push(vscode.commands.registerCommand('rejadx.openVirtualFile', async (uriOrString: vscode.Uri | string) => {
    const uri = typeof uriOrString === 'string' ? vscode.Uri.parse(uriOrString) : uriOrString;
    if (!uri || uri.scheme !== 'jadx') {
      return;
    }

    // Bypass cached content when opening from tree.
    contentProvider.invalidate(uri.toString());
    await vscode.commands.executeCommand('vscode.open', uri, { preview: false });
  }));

  context.subscriptions.push(vscode.commands.registerCommand('jadx.openSideBySide', async () => {
    const editor = vscode.window.activeTextEditor;
    if (!editor || editor.document.uri.scheme !== 'jadx') {
      return;
    }

    const current = editor.document.uri;
    const query = current.query || '';
    const isSmali = query.includes('type=smali');
    const nextQuery = isSmali
      ? query.replace('type=smali', 'type=java')
      : query.includes('type=java')
        ? query.replace('type=java', 'type=smali')
        : (query ? `${query}&type=smali` : 'type=smali');

    const opposite = current.with({ query: nextQuery });
    await vscode.commands.executeCommand('vscode.open', opposite, vscode.ViewColumn.Beside);
  }));

  context.subscriptions.push(vscode.commands.registerCommand('rejadx.reopenJavaFile', async () => {
    const editor = vscode.window.activeTextEditor;
    if (!editor || editor.document.uri.scheme !== 'jadx') {
      vscode.window.showWarningMessage('ReJadx: open a jadx virtual Java/Smali file first.');
      return;
    }

    const current = editor.document.uri;
    if (current.path.startsWith('/resources/')) {
      vscode.window.showWarningMessage('ReJadx: Reopen Java File works only for decompiled classes.');
      return;
    }

    const query = current.query || '';
    const javaQuery = query.includes('type=smali')
      ? query.replace('type=smali', 'type=java')
      : (query.includes('type=java') ? query : (query ? `${query}&type=java` : 'type=java'));
    const target = current.with({ query: javaQuery });

    contentProvider.invalidate(target.toString());
    await vscode.commands.executeCommand('vscode.open', target, {
      preview: false,
      selection: editor.selection
    });
  }));

  context.subscriptions.push(vscode.commands.registerCommand('jadx.exportProGuard', async () => {
    const readyClient = getClient() ?? await ensureClientStarted();

    try {
      const res = await readyClient.sendRequest('jadx/exportMappings', {}) as ExportMappingsResult;
      const mapping = res?.mapping ?? '';

      const target = await vscode.window.showSaveDialog({
        title: 'Export ProGuard Mapping',
        saveLabel: 'Export',
        filters: { 'Text Files': ['txt'], 'All Files': ['*'] },
        defaultUri: vscode.Uri.file('mapping.txt')
      });
      if (!target) {
        return;
      }

      await fs.writeFile(target.fsPath, mapping, 'utf8');
      vscode.window.showInformationMessage(`ReJadx: ProGuard mapping exported to ${target.fsPath}`);
    } catch (err) {
      vscode.window.showErrorMessage(`ReJadx: export mappings failed: ${err}`);
    }
  }));

  context.subscriptions.push(vscode.commands.registerCommand('rejadx.resetProjectCodeCache', async () => {
    try {
      await persistCurrentProjectTabs();
      await closeVirtualTabsAfterSave();

      if (lastLoadedApkPath) {
        dashboardProvider.notifyProjectLoading(lastLoadedApkPath);
      }

      const readyClient = getClient() ?? await ensureClientStarted();
      const res = await readyClient.sendRequest('workspace/executeCommand', {
        command: 'rejadx.resetCodeCache',
        arguments: []
      }) as ResetCodeCacheResult;

      if (!res?.reset || res.loaded === false) {
        const reason = res?.error ?? 'Failed to reset code cache';
        if (lastLoadedApkPath) {
          dashboardProvider.notifyProjectLoadFailed(reason);
        }
        vscode.window.showErrorMessage(`ReJadx: ${reason}`);
        return;
      }

      const packages = await readyClient.sendRequest('workspace/executeCommand', {
        command: 'rejadx.getPackages',
        arguments: []
      }) as PackageNode[];
      classTreeProvider.setRoots(packages);

      if (lastLoadedApkPath) {
        dashboardProvider.notifyProjectLoaded(res.classCount ?? 0);
        await restoreProjectTabs(lastLoadedApkPath);
      }

      const cacheInfo = res.cacheDir ? ` (${res.cacheDir})` : '';
      vscode.window.showInformationMessage(`ReJadx: code cache reset${cacheInfo}.`);
    } catch (err) {
      vscode.window.showErrorMessage(`ReJadx: reset code cache failed: ${err}`);
    }
  }));

  context.subscriptions.push(vscode.commands.registerCommand('jadx.searchCode', async () => {
    const query = await vscode.window.showInputBox({
      prompt: 'Search in decompiled code',
      placeHolder: 'Enter text to search',
      ignoreFocusOut: true
    });
    if (query === undefined || query.length === 0) {
      return;
    }

    const mode = await vscode.window.showQuickPick([
      { label: 'Plain (Case-insensitive)', caseSensitive: false, regex: false },
      { label: 'Plain (Case-sensitive)', caseSensitive: true, regex: false },
      { label: 'Regex (Case-insensitive)', caseSensitive: false, regex: true },
      { label: 'Regex (Case-sensitive)', caseSensitive: true, regex: true }
    ], {
      title: 'Search mode',
      ignoreFocusOut: true
    });
    if (!mode) {
      return;
    }

    const cfgMax = getReJadxSettings().searchMaxResults;
    const maxResults = Number.isFinite(cfgMax) && cfgMax > 0 ? Math.floor(cfgMax) : 50;
    await vscode.window.withProgress({
      location: vscode.ProgressLocation.Notification,
      title: `ReJadx search: "${query}"`,
      cancellable: false
    }, async () => {
      const readyClient = getClient() ?? await ensureClientStarted();
      try {
        const hits = await readyClient.sendRequest('workspace/executeCommand', {
          command: 'rejadx.searchCode',
          arguments: [{
            query,
            caseSensitive: mode.caseSensitive,
            regex: mode.regex,
            maxResults
          }]
        }) as SearchResult[];

        if (!Array.isArray(hits) || hits.length === 0) {
          vscode.window.showInformationMessage(`ReJadx: no matches for "${query}".`);
          return;
        }

        const locations = hits.map(h => new vscode.Location(
          vscode.Uri.parse(h.uri),
          new vscode.Range(h.line, h.character, h.line, h.character + Math.max(1, h.length))
        ));

        let anchorUri: vscode.Uri;
        let anchorPos: vscode.Position;

        const active = vscode.window.activeTextEditor;
        if (active) {
          anchorUri = active.document.uri;
          anchorPos = active.selection.active;
        } else {
          const tempUri = vscode.Uri.parse('jadx:///__references_anchor__/SearchAnchor.java?type=java');
          contentProvider.update(tempUri.toString(), '');
          await vscode.commands.executeCommand('vscode.open', tempUri, { preview: false });
          await new Promise(resolve => setTimeout(resolve, 250));

          const now = vscode.window.activeTextEditor;
          if (now) {
            anchorUri = now.document.uri;
            anchorPos = now.selection.active;
          } else {
            anchorUri = tempUri;
            anchorPos = new vscode.Position(0, 0);
          }
        }

        await vscode.commands.executeCommand(
          'editor.action.showReferences',
          anchorUri,
          anchorPos,
          locations
        );

        if (hits.length >= maxResults) {
          vscode.window.showInformationMessage(`ReJadx: showing first ${maxResults} matches for "${query}".`);
        }
      } catch (err) {
        vscode.window.showErrorMessage(`ReJadx: search failed: ${err}`);
      }
    });
  }));
}

export async function deactivate(): Promise<void> {
  try {
    if (persistOnDeactivate) {
      await persistOnDeactivate();
    }
    if (closeTabsOnDeactivate) {
      await closeTabsOnDeactivate();
    }
  } catch {
    // ignore
  }
  await stopLanguageClient();
}
