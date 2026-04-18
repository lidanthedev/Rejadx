import * as vscode from 'vscode';
import { startLanguageClient, stopLanguageClient, getClient } from './languageClient';
import { DashboardProvider, TelemetryUpdate } from './views/DashboardProvider';
import { ClassTreeProvider, PackageNode } from './views/ClassTreeProvider';

interface SourceReadyParams {
  uri: string;
  content: string;
  languageId: string;
}

function languageIdForJadxUri(uri: vscode.Uri): string {
  const q = uri.query || '';
  return q.includes('type=smali') ? 'plaintext' : 'java';
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
  private readonly _inflight = new Map<string, Thenable<string>>();
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
      return pending;
    }

    const request = lc.sendRequest('workspace/executeCommand', {
      command: 'rejadx.getSource',
      arguments: [key]
    }).then((res) => {
      const content = typeof res === 'string'
        ? res
        : (res as { content?: string } | undefined)?.content ?? '// Empty source';
      this.update(key, content);
      return content;
    }).catch((err) => {
      const message = `// Failed to load source: ${err instanceof Error ? err.message : String(err)}`;
      this.update(key, message);
      return message;
    }).finally(() => {
      this._inflight.delete(key);
    });

    this._inflight.set(key, request);
    return request;
  }
}

export async function activate(context: vscode.ExtensionContext): Promise<void> {
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
    const lc = getClient();
    if (!lc) {
      vscode.window.showErrorMessage('ReJadx: Language server not ready.');
      return;
    }
    try {
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

      dashboardProvider.notifyProjectLoaded(result.classCount ?? 0);

      const packages = await lc.sendRequest('workspace/executeCommand', {
        command: 'rejadx.getPackages',
        arguments: []
      }) as PackageNode[];
      classTreeProvider.setRoots(packages);
    } catch (err) {
      vscode.window.showErrorMessage(`ReJadx: ${err}`);
    }
  });

  await startLanguageClient(context);

  const lc = getClient()!;

  lc.onNotification('rejadx/sourceReady', (params: SourceReadyParams) => {
    contentProvider.update(params.uri, params.content);

    const uri = vscode.Uri.parse(params.uri);
    const openDoc = vscode.workspace.textDocuments.find(d => d.uri.toString() === uri.toString());
    if (openDoc) {
      const desired = params.languageId === 'smali' ? 'plaintext' : 'java';
      if (openDoc.languageId !== desired) {
        void vscode.languages.setTextDocumentLanguage(openDoc, desired).then(() => undefined, () => undefined);
      }
    }
  });

  lc.onNotification('rejadx/telemetry', (params: TelemetryUpdate) => {
    dashboardProvider.updateTelemetry(params);
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

    const comment = await vscode.window.showInputBox({
      prompt: 'Add comment',
      placeHolder: 'Enter comment text',
      ignoreFocusOut: true,
      validateInput: (value) => value.trim().length === 0 ? 'Comment cannot be empty' : null
    });
    if (!comment) {
      return;
    }

    const client = getClient();
    if (!client) {
      vscode.window.showErrorMessage('ReJadx: Language server not ready.');
      return;
    }

    const pos = editor.selection.active;
    try {
      await client.sendRequest('jadx/addComment', {
        uri: uri.toString(),
        line: pos.line,
        character: pos.character,
        comment: comment.trim(),
        style: 'LINE'
      });

      // Force VS Code to re-fetch this virtual document.
      contentProvider.invalidate(uri.toString());
    } catch (err) {
      vscode.window.showErrorMessage(`ReJadx: add comment failed: ${err}`);
    }
  }));
}

export async function deactivate(): Promise<void> {
  await stopLanguageClient();
}
