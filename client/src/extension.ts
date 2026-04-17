import * as vscode from 'vscode';
import { startLanguageClient, stopLanguageClient } from './languageClient';
import { DashboardProvider } from './views/DashboardProvider';
import { ClassTreeProvider } from './views/ClassTreeProvider';

export async function activate(context: vscode.ExtensionContext): Promise<void> {
  // Register the virtual document scheme for jadx:// URIs.
  // Files are NEVER written to disk — all content comes from the LSP server.
  const jadxScheme = 'jadx';
  const contentProvider = new (class implements vscode.TextDocumentContentProvider {
    readonly onDidChangeEmitter = new vscode.EventEmitter<vscode.Uri>();
    readonly onDidChange = this.onDidChangeEmitter.event;

    provideTextDocumentContent(_uri: vscode.Uri): string {
      // Stub — will be driven by LSP content notifications in the next milestone.
      return '// Loading…';
    }
  })();

  // Must register BEFORE starting the LSP client to avoid a race on first
  // textDocument/didOpen notification from the server.
  context.subscriptions.push(
    vscode.workspace.registerTextDocumentContentProvider(jadxScheme, contentProvider)
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

  await startLanguageClient(context);
}

export async function deactivate(): Promise<void> {
  await stopLanguageClient();
}
