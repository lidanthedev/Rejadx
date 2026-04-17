import * as vscode from 'vscode';

export interface TelemetryUpdate {
  heapUsedBytes: number;
  heapMaxBytes: number;
  classCount: number;
  status: string;
}

/**
 * Webview panel shown in the ReJadx activity-bar container.
 * Uses native VS Code CSS custom properties (--vscode-*) for theming.
 * @vscode/webview-ui-toolkit is intentionally avoided — it was deprecated in VS Code 1.90.
 */
export class DashboardProvider implements vscode.WebviewViewProvider {
  public static readonly viewType = 'rejadx.dashboard';

  private _view: vscode.WebviewView | undefined;
  private _onOpenApkFn: ((path: string) => Promise<void>) | undefined;
  private _onBrowseFn: (() => Promise<void>) | undefined;

  constructor(private readonly _extensionUri: vscode.Uri) {}

  resolveWebviewView(
    webviewView: vscode.WebviewView,
    _context: vscode.WebviewViewResolveContext,
    _token: vscode.CancellationToken
  ): void {
    this._view = webviewView;
    webviewView.webview.options = {
      enableScripts: true,
      localResourceRoots: [vscode.Uri.joinPath(this._extensionUri, 'media')]
    };
    webviewView.webview.html = this._buildHtml(webviewView.webview);

    webviewView.webview.onDidReceiveMessage(async (msg: { command: string; apkPath?: string }) => {
      if (msg.command === 'openApk' && msg.apkPath) {
        await this._onOpenApkFn?.(msg.apkPath);
      } else if (msg.command === 'browseApk') {
        await this._onBrowseFn?.();
      }
    });

    webviewView.onDidDispose(() => { this._view = undefined; });
  }

  setOpenApkHandler(fn: (path: string) => Promise<void>): void { this._onOpenApkFn = fn; }
  setBrowseHandler(fn: () => Promise<void>): void { this._onBrowseFn = fn; }

  setApkPath(path: string): void {
    this._view?.webview.postMessage({ command: 'setApkPath', path });
  }

  notifyProjectLoaded(classCount: number): void {
    this._view?.webview.postMessage({ command: 'projectLoaded', classCount });
  }

  notifyProjectLoadFailed(message: string): void {
    this._view?.webview.postMessage({ command: 'projectLoadFailed', message });
  }

  updateTelemetry(params: TelemetryUpdate): void {
    this._view?.webview.postMessage({ command: 'telemetry', ...params });
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
  <title>ReJadx</title>
  <style>
    body {
      font-family: var(--vscode-font-family);
      font-size: var(--vscode-font-size);
      color: var(--vscode-foreground);
      background: var(--vscode-sideBar-background);
      padding: 8px;
      margin: 0;
    }
    h2 {
      font-size: 0.82em;
      text-transform: uppercase;
      letter-spacing: 0.08em;
      color: var(--vscode-sideBarSectionHeader-foreground);
      margin: 0 0 8px 0;
    }
    .row { display: flex; gap: 4px; align-items: center; margin-bottom: 6px; }
    input[type="text"] {
      flex: 1;
      background: var(--vscode-input-background);
      color: var(--vscode-input-foreground);
      border: 1px solid var(--vscode-input-border, transparent);
      padding: 3px 6px;
      font-size: var(--vscode-font-size);
      font-family: var(--vscode-font-family);
      min-width: 0;
    }
    button {
      background: var(--vscode-button-background);
      color: var(--vscode-button-foreground);
      border: none;
      padding: 3px 8px;
      cursor: pointer;
      font-size: var(--vscode-font-size);
      white-space: nowrap;
      flex-shrink: 0;
    }
    button:hover { background: var(--vscode-button-hoverBackground); }
    #init-status { font-size: 0.85em; color: var(--vscode-descriptionForeground); }
    #dash { display: none; }
    .kv {
      display: flex;
      justify-content: space-between;
      font-size: 0.85em;
      margin-bottom: 4px;
    }
    .kv .val { color: var(--vscode-charts-green); font-weight: 600; }
    .mem-wrap {
      height: 6px;
      background: var(--vscode-progressBar-background, #444);
      border-radius: 3px;
      margin: 6px 0;
      overflow: hidden;
    }
    #mem-fill {
      height: 100%;
      background: var(--vscode-charts-blue);
      width: 0%;
      transition: width 0.4s ease;
      border-radius: 3px;
    }
    #mem-fill.high { background: var(--vscode-charts-orange); }
    #mem-fill.critical { background: var(--vscode-charts-red); }
    #mem-label { font-size: 0.8em; color: var(--vscode-descriptionForeground); }
  </style>
</head>
<body>
  <div id="init">
    <h2>ReJadx</h2>
    <div class="row">
      <input id="apk-path" type="text" placeholder="/path/to/app.apk" />
      <button id="browse-btn" title="Browse">…</button>
    </div>
    <div class="row">
      <button id="open-btn" style="width:100%">Open</button>
    </div>
    <div id="init-status">No project loaded.</div>
  </div>

  <div id="dash">
    <h2>ReJadx</h2>
    <div class="kv"><span>Status</span><span id="proj-status" class="val">ready</span></div>
    <div class="kv"><span>Classes</span><span id="class-count" class="val">0</span></div>
    <div class="mem-wrap"><div id="mem-fill"></div></div>
    <div id="mem-label">Heap: — MB / — MB</div>
  </div>

  <script nonce="${nonce}">
    const vscode = acquireVsCodeApi();

    document.getElementById('open-btn').addEventListener('click', () => {
      const p = document.getElementById('apk-path').value.trim();
      if (p) {
        vscode.postMessage({ command: 'openApk', apkPath: p });
        document.getElementById('init-status').textContent = 'Loading…';
        document.getElementById('open-btn').disabled = true;
      }
    });

    document.getElementById('browse-btn').addEventListener('click', () => {
      vscode.postMessage({ command: 'browseApk' });
    });

    window.addEventListener('message', e => {
      const msg = e.data;
      if (msg.command === 'setApkPath') {
        document.getElementById('apk-path').value = msg.path;
      } else if (msg.command === 'projectLoaded') {
        document.getElementById('init').style.display = 'none';
        document.getElementById('dash').style.display = 'block';
        document.getElementById('class-count').textContent = msg.classCount;
      } else if (msg.command === 'projectLoadFailed') {
        document.getElementById('init-status').textContent = msg.message;
        document.getElementById('open-btn').disabled = false;
      } else if (msg.command === 'telemetry') {
        const pct = msg.heapMaxBytes > 0 ? (msg.heapUsedBytes / msg.heapMaxBytes * 100) : 0;
        const fill = document.getElementById('mem-fill');
        fill.style.width = pct.toFixed(1) + '%';
        fill.className = pct > 90 ? 'critical' : pct > 75 ? 'high' : '';
        const toMb = v => (v / 1048576).toFixed(0);
        document.getElementById('mem-label').textContent =
          'Heap: ' + toMb(msg.heapUsedBytes) + ' MB / ' + toMb(msg.heapMaxBytes) + ' MB';
        document.getElementById('proj-status').textContent = msg.status;
        if (msg.classCount > 0) {
          document.getElementById('class-count').textContent = msg.classCount;
        }
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
