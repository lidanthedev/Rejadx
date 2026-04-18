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
  private _onRestartProjectFn: (() => Promise<void>) | undefined;
  private _onStopProjectFn: (() => Promise<void>) | undefined;
  private _onViewReadyFn: (() => void) | undefined;
  private _state: {
    phase: 'init' | 'loading' | 'loaded';
    apkPath: string;
    initStatus: string;
    classCount: number;
    recentProjects: string[];
    telemetry?: TelemetryUpdate;
  } = {
    phase: 'init',
    apkPath: '',
    initStatus: 'No project loaded.',
    classCount: 0,
    recentProjects: []
  };

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

    this._pushState();
    this._onViewReadyFn?.();

    webviewView.webview.onDidReceiveMessage(async (msg: { command: string; apkPath?: string }) => {
      if (msg.command === 'openApk' && msg.apkPath) {
        await this._onOpenApkFn?.(msg.apkPath);
      } else if (msg.command === 'browseApk') {
        await this._onBrowseFn?.();
      } else if (msg.command === 'restartProject') {
        await this._onRestartProjectFn?.();
      } else if (msg.command === 'stopProject') {
        await this._onStopProjectFn?.();
      }
    });

    webviewView.onDidChangeVisibility(() => {
      if (webviewView.visible) {
        this._pushState();
        this._onViewReadyFn?.();
      }
    });

    webviewView.onDidDispose(() => { this._view = undefined; });
  }

  setOpenApkHandler(fn: (path: string) => Promise<void>): void { this._onOpenApkFn = fn; }
  setBrowseHandler(fn: () => Promise<void>): void { this._onBrowseFn = fn; }
  setRestartProjectHandler(fn: () => Promise<void>): void { this._onRestartProjectFn = fn; }
  setStopProjectHandler(fn: () => Promise<void>): void { this._onStopProjectFn = fn; }
  setViewReadyHandler(fn: () => void): void { this._onViewReadyFn = fn; }

  setRecentProjects(projects: string[]): void {
    this._state.recentProjects = [...projects];
    this._pushState();
  }

  setApkPath(path: string): void {
    this._state.apkPath = path;
    this._pushState();
  }

  notifyProjectLoading(path: string): void {
    this._state.phase = 'loading';
    this._state.apkPath = path;
    this._state.initStatus = 'Loading...';
    this._pushState();
  }

  notifyProjectLoaded(classCount: number): void {
    this._state.phase = 'loaded';
    this._state.classCount = classCount;
    this._state.initStatus = 'Loaded';
    this._pushState();
  }

  notifyProjectLoadFailed(message: string): void {
    this._state.phase = this._state.classCount > 0 ? 'loaded' : 'init';
    this._state.initStatus = message;
    this._pushState();
  }

  notifyProjectClosed(): void {
    this._state.phase = 'init';
    this._state.classCount = 0;
    this._state.telemetry = undefined;
    this._state.initStatus = 'No project loaded.';
    this._pushState();
  }

  updateTelemetry(params: TelemetryUpdate): void {
    this._state.telemetry = params;
    if (params.classCount > 0) {
      this._state.classCount = params.classCount;
      if (this._state.phase !== 'loading') {
        this._state.phase = 'loaded';
      }
    }
    this._pushState();
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
    .recent {
      margin-top: 10px;
      border-top: 1px solid var(--vscode-panel-border);
      padding-top: 8px;
    }
    .recent-item {
      width: 100%;
      text-align: left;
      margin-bottom: 4px;
      background: var(--vscode-inputOption-activeBackground, var(--vscode-button-secondaryBackground));
      color: var(--vscode-inputOption-activeForeground, var(--vscode-button-secondaryForeground));
      border: 1px solid var(--vscode-inputOption-activeBorder, transparent);
      overflow: hidden;
      text-overflow: ellipsis;
    }
    .recent-placeholder {
      font-size: 0.85em;
      color: var(--vscode-descriptionForeground);
    }
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
    <div class="row">
      <button id="restart-btn" style="flex:1">Restart</button>
      <button id="stop-btn" style="flex:1">Stop</button>
    </div>
    <div class="kv"><span>Status</span><span id="proj-status" class="val">ready</span></div>
    <div class="kv"><span>Classes</span><span id="class-count" class="val">0</span></div>
    <div class="mem-wrap"><div id="mem-fill"></div></div>
    <div id="mem-label">Heap: — MB / — MB</div>
  </div>

  <div id="recent-wrap" class="recent">
    <h2>Recent</h2>
    <div id="recent-list"></div>
  </div>

  <script nonce="${nonce}">
    const vscode = acquireVsCodeApi();

    document.getElementById('open-btn').addEventListener('click', () => {
      const p = document.getElementById('apk-path').value.trim();
      if (p) {
        vscode.postMessage({ command: 'openApk', apkPath: p });
        document.getElementById('init-status').textContent = 'Loading...';
        document.getElementById('open-btn').disabled = true;
      }
    });

    document.getElementById('browse-btn').addEventListener('click', () => {
      vscode.postMessage({ command: 'browseApk' });
    });

    document.getElementById('restart-btn').addEventListener('click', () => {
      vscode.postMessage({ command: 'restartProject' });
    });
    document.getElementById('stop-btn').addEventListener('click', () => {
      vscode.postMessage({ command: 'stopProject' });
    });

    window.addEventListener('message', e => {
      const msg = e.data;
      if (msg.command === 'state') {
        const pathInput = document.getElementById('apk-path');
        if (typeof msg.apkPath === 'string' && msg.apkPath.length > 0) {
          pathInput.value = msg.apkPath;
        }

        const init = document.getElementById('init');
        const dash = document.getElementById('dash');
        const recentWrap = document.getElementById('recent-wrap');
        const initStatus = document.getElementById('init-status');
        const openBtn = document.getElementById('open-btn');

        const phase = (msg.phase === 'init' || msg.phase === 'loading' || msg.phase === 'loaded')
          ? msg.phase
          : 'init';

        const showDashboard = phase === 'loading' || phase === 'loaded';
        init.style.display = showDashboard ? 'none' : 'block';
        dash.style.display = showDashboard ? 'block' : 'none';
        recentWrap.style.display = phase === 'init' ? 'block' : 'none';

        initStatus.textContent = msg.initStatus || (phase === 'loading' ? 'Loading...' : 'No project loaded.');
        openBtn.disabled = phase === 'loading';

        if (typeof msg.classCount === 'number') {
          document.getElementById('class-count').textContent = String(msg.classCount);
        }

        if (msg.telemetry) {
          const t = msg.telemetry;
          const pct = t.heapMaxBytes > 0 ? (t.heapUsedBytes / t.heapMaxBytes * 100) : 0;
          const fill = document.getElementById('mem-fill');
          fill.style.width = pct.toFixed(1) + '%';
          fill.className = pct > 90 ? 'critical' : pct > 75 ? 'high' : '';
          const toMb = v => (v / 1048576).toFixed(0);
          document.getElementById('mem-label').textContent =
            'Heap: ' + toMb(t.heapUsedBytes) + ' MB / ' + toMb(t.heapMaxBytes) + ' MB';
          document.getElementById('proj-status').textContent = t.status;
          if (t.classCount > 0) {
            document.getElementById('class-count').textContent = String(t.classCount);
          }
        }

        const recent = Array.isArray(msg.recentProjects) ? msg.recentProjects : [];
        const recentList = document.getElementById('recent-list');
        const initStatusEl = document.getElementById('init-status');
        recentList.innerHTML = '';
        if (recent.length === 0) {
          if (initStatusEl) {
            initStatusEl.textContent = msg.initStatus || 'No recent projects.';
          }
          const empty = document.createElement('div');
          empty.className = 'recent-placeholder';
          empty.textContent = 'No recent projects.';
          recentList.appendChild(empty);
        } else {
          for (const path of recent) {
            const btn = document.createElement('button');
            btn.className = 'recent-item';
            btn.title = path;
            btn.textContent = path;
            btn.addEventListener('click', () => {
              vscode.postMessage({ command: 'openApk', apkPath: path });
            });
            recentList.appendChild(btn);
          }
        }
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

  private _pushState(): void {
    this._view?.webview.postMessage({
      command: 'state',
      phase: this._state.phase,
      apkPath: this._state.apkPath,
      initStatus: this._state.initStatus,
      classCount: this._state.classCount,
      recentProjects: this._state.recentProjects,
      telemetry: this._state.telemetry
    });
  }
}
