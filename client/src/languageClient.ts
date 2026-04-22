import * as fs from 'fs';
import * as path from 'path';
import { spawnSync } from 'child_process';
import * as vscode from 'vscode';
import {
  LanguageClient,
  LanguageClientOptions,
  ServerOptions,
  TransportKind
} from 'vscode-languageclient/node';

let client: LanguageClient | undefined;
let clientOutputChannel: vscode.OutputChannel | undefined;

export interface JadxJarValidation {
  ok: boolean;
  path: string;
  reason?: string;
}

function parseJavaMajor(versionOutput: string): number | null {
  const match = versionOutput.match(/version\s+"([^"]+)"/i);
  if (!match) {
    return null;
  }

  const raw = match[1];
  if (raw.startsWith('1.')) {
    const legacy = Number.parseInt(raw.split('.')[1], 10);
    return Number.isFinite(legacy) ? legacy : null;
  }

  const major = Number.parseInt(raw.split(/[.+-]/)[0], 10);
  return Number.isFinite(major) ? major : null;
}

function ensureJava21Available(): void {
  const probe = spawnSync('java', ['-version'], { encoding: 'utf8' });
  const output = `${probe.stderr ?? ''}\n${probe.stdout ?? ''}`;

  if (probe.error) {
    throw new Error('Java runtime not found');
  }

  const major = parseJavaMajor(output);
  if (major === null || major < 21) {
    throw new Error(`Java 21+ is required (detected: ${major === null ? 'unknown' : major})`);
  }
}


export function validateConfiguredJadxJarPath(): JadxJarValidation {
  const rawPath = (getReJadxSettings().jadxJarPath ?? '').trim();
  if (!rawPath) {
    return {
      ok: false,
      path: '',
      reason: 'JADX JAR path is not configured in settings.'
    };
  }

  try {
    if (!fs.existsSync(rawPath)) {
      return {
        ok: false,
        path: rawPath,
        reason: `JADX JAR not found: ${rawPath}`
      };
    }

    if (!fs.statSync(rawPath).isFile()) {
      return {
        ok: false,
        path: rawPath,
        reason: `JADX path is not a file: ${rawPath}`
      };
    }
  } catch (e) {
    return {
      ok: false,
      path: rawPath,
      reason: `Cannot access JADX JAR path: ${e instanceof Error ? e.message : String(e)}`
    };
  }

  if (!rawPath.toLowerCase().endsWith('.jar')) {
    return {
      ok: false,
      path: rawPath,
      reason: `JADX path is not a .jar file: ${rawPath}`
    };
  }

  return { ok: true, path: rawPath };
}

export async function startLanguageClient(context: vscode.ExtensionContext): Promise<void> {
  const serverJar = path.join(context.extensionPath, 'bin', 'jadx-server.jar');

  if (!fs.existsSync(serverJar)) {
    const msg = 'ReJadx: bundled language server is missing. Reinstall the extension.';
    vscode.window.showErrorMessage(msg);
    throw new Error(msg);
  }

  const jadxJar = validateConfiguredJadxJarPath();
  if (!jadxJar.ok) {
    const msg = `ReJadx requires a local JADX JAR path. Set 'rejadx.jadxJarPath' in settings. ${jadxJar.reason ?? ''}`.trim();
    vscode.window.showErrorMessage(msg);
    throw new Error(msg);
  }

  try {
    ensureJava21Available();
  } catch (e) {
    const reason = e instanceof Error ? e.message : String(e);
    const msg = `ReJadx requires Java 21+ (JRE). Install Java 21 and restart VS Code. Details: ${reason}`;
    vscode.window.showErrorMessage(msg);
    throw new Error(msg);
  }

  const settings = getReJadxSettings();

  const serverOptions: ServerOptions = {
    command: 'java',
    args: [...settings.languageServerJvmArgs, '-cp', `${serverJar}${path.delimiter}${jadxJar.path}`, 'dev.rejadx.server.ReJadxServer'],
    transport: TransportKind.stdio,
    options: {
      env: { ...process.env }
    }
  };

  if (!clientOutputChannel) {
    clientOutputChannel = vscode.window.createOutputChannel('ReJadx Language Server');
  }

  const clientOptions: LanguageClientOptions = {
    // Only activate for virtual jadx:// documents — nothing ever touches disk.
    documentSelector: [{ scheme: 'jadx' }],
    outputChannel: clientOutputChannel
  };

  client = new LanguageClient(
    'rejadx',
    'ReJadx Language Server',
    serverOptions,
    clientOptions
  );

  context.subscriptions.push(client);
  try {
    await client.start();
  } catch (e) {
    const reason = e instanceof Error ? e.message : String(e);
    const msg = `ReJadx Language Server failed to start. Ensure Java 21+ is installed. Details: ${reason}`;
    vscode.window.showErrorMessage(msg);
    throw e;
  }
}

export interface ReJadxClientSettings {
  searchMaxResults: number;
  customJadxArgs: string;
  languageServerJvmArgs: string[];
  enableExternalPlugins: boolean;
  enableCodeCache: boolean;
  jadxJarPath: string;
}

export function getReJadxSettings(): ReJadxClientSettings {
  const cfg = vscode.workspace.getConfiguration('rejadx');
  const searchMaxResults = cfg.get<number>('search.maxResults', 50);
  const customJadxArgs = cfg.get<string>('customJadxArgs', '');
  const languageServerJvmArgs = cfg.get<string[]>('languageServer.jvmArgs', []);
  const enableExternalPlugins = cfg.get<boolean>('enableExternalPlugins', false);
  const enableCodeCache = cfg.get<boolean>('enableCodeCache', true);
  const jadxJarPath = cfg.get<string>('jadxJarPath', '');
  return {
    searchMaxResults,
    customJadxArgs,
    languageServerJvmArgs,
    enableExternalPlugins,
    enableCodeCache,
    jadxJarPath
  };
}

export async function stopLanguageClient(): Promise<void> {
  if (client) {
    await client.stop();
    client = undefined;
  }
}

export async function stopLanguageClientAndDisposeOutput(): Promise<void> {
  await stopLanguageClient();
  if (clientOutputChannel) {
    clientOutputChannel.dispose();
    clientOutputChannel = undefined;
  }
}

export function getClient(): LanguageClient | undefined {
  return client;
}
