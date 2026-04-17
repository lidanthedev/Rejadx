import * as vscode from 'vscode';

/**
 * Webview panel shown in the top slot of the ReJadx activity-bar container.
 * Uses native VS Code CSS custom properties (--vscode-*) for theming.
 * @vscode/webview-ui-toolkit is intentionally avoided — it was deprecated in VS Code 1.90.
 */
export class DashboardProvider implements vscode.WebviewViewProvider {
  public static readonly viewType = 'rejadx.dashboard';

  constructor(private readonly _extensionUri: vscode.Uri) {}

  resolveWebviewView(
    webviewView: vscode.WebviewView,
    _context: vscode.WebviewViewResolveContext,
    _token: vscode.CancellationToken
  ): void {
    webviewView.webview.options = {
      enableScripts: true,
      localResourceRoots: [vscode.Uri.joinPath(this._extensionUri, 'media')]
    };

    webviewView.webview.html = this._buildHtml(webviewView.webview);

    webviewView.webview.onDidReceiveMessage(async (message: { command: string; apkPath?: string }) => {
      if (message.command === 'openApk' && message.apkPath) {
        await this._onOpenApk(message.apkPath);
      }
    });
  }

  private async _onOpenApk(apkPath: string): Promise<void> {
    // TODO: forward to LSP via workspace/executeCommand → rejadx.openApk
    vscode.window.showInformationMessage(`Opening APK: ${apkPath}`);
  }

  private _buildHtml(webview: vscode.Webview): string {
    const nonce = this._getNonce();
    const csp = [
      `default-src 'none'`,
      `style-src ${webview.cspSource} 'unsafe-inline'`,
      `script-src 'nonce-${nonce}'`
    ].join('; ');

    return /* html */ `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta http-equiv="Content-Security-Policy" content="${csp}">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>ReJadx Dashboard</title>
  <style>
    body {
      font-family: var(--vscode-font-family);
      font-size: var(--vscode-font-size);
      color: var(--vscode-foreground);
      background-color: var(--vscode-sideBar-background);
      padding: 8px;
      margin: 0;
    }
    h2 {
      font-size: 0.85em;
      text-transform: uppercase;
      letter-spacing: 0.08em;
      color: var(--vscode-sideBarSectionHeader-foreground);
      margin: 0 0 8px 0;
    }
    .row { display: flex; gap: 6px; align-items: center; }
    input[type="text"] {
      flex: 1;
      background: var(--vscode-input-background);
      color: var(--vscode-input-foreground);
      border: 1px solid var(--vscode-input-border, transparent);
      padding: 3px 6px;
      font-size: var(--vscode-font-size);
      font-family: var(--vscode-font-family);
    }
    button {
      background: var(--vscode-button-background);
      color: var(--vscode-button-foreground);
      border: none;
      padding: 4px 10px;
      cursor: pointer;
      font-size: var(--vscode-font-size);
      white-space: nowrap;
    }
    button:hover { background: var(--vscode-button-hoverBackground); }
    #status {
      margin-top: 8px;
      font-size: 0.85em;
      color: var(--vscode-descriptionForeground);
    }
  </style>
</head>
<body>
  <h2>ReJadx</h2>
  <div class="row">
    <input id="apk-path" type="text" placeholder="/path/to/app.apk or .jadx" />
    <button id="open-btn">Open</button>
  </div>
  <div id="status">No project loaded.</div>

  <script nonce="${nonce}">
    const vscode = acquireVsCodeApi();
    document.getElementById('open-btn').addEventListener('click', () => {
      const apkPath = document.getElementById('apk-path').value.trim();
      if (apkPath) {
        vscode.postMessage({ command: 'openApk', apkPath });
        document.getElementById('status').textContent = 'Loading ' + apkPath + '…';
      }
    });
  </script>
</body>
</html>`;
  }

  private _getNonce(): string {
    const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
    return Array.from({ length: 32 }, () => chars[Math.floor(Math.random() * chars.length)]).join('');
  }
}
