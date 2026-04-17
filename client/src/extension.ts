import * as vscode from 'vscode';
import { startLanguageClient, stopLanguageClient, getClient } from './languageClient';
import { DashboardProvider, TelemetryUpdate } from './views/DashboardProvider';
import { ClassTreeProvider, PackageNode } from './views/ClassTreeProvider';

interface SourceReadyParams {
  uri: string;
  content: string;
  languageId: string;
}

class JadxContentProvider implements vscode.TextDocumentContentProvider {
  private readonly _cache = new Map<string, string>();
  readonly onDidChangeEmitter = new vscode.EventEmitter<vscode.Uri>();
  readonly onDidChange = this.onDidChangeEmitter.event;

  update(uriStr: string, content: string): void {
    this._cache.set(uriStr, content);
    this.onDidChangeEmitter.fire(vscode.Uri.parse(uriStr));
  }

  provideTextDocumentContent(uri: vscode.Uri): string {
    return this._cache.get(uri.toString()) ?? '// Loading\u2026';
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
      }) as { classCount: number };

      dashboardProvider.notifyProjectLoaded(result.classCount);

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
  });

  lc.onNotification('rejadx/telemetry', (params: TelemetryUpdate) => {
    dashboardProvider.updateTelemetry(params);
  });
}

export async function deactivate(): Promise<void> {
  await stopLanguageClient();
}
