import * as path from 'path';
import * as vscode from 'vscode';
import {
  LanguageClient,
  LanguageClientOptions,
  ServerOptions,
  TransportKind
} from 'vscode-languageclient/node';

let client: LanguageClient | undefined;
let clientOutputChannel: vscode.OutputChannel | undefined;

export async function startLanguageClient(context: vscode.ExtensionContext): Promise<void> {
  // The fat JAR lives one level above the extension directory (monorepo layout):
  //   <repo>/client/   ← context.extensionPath
  //   <repo>/server/build/libs/rejadx-server-all.jar
  const workspaceRoot = path.resolve(context.extensionPath, '..');
  const serverJar = path.join(workspaceRoot, 'server', 'build', 'libs', 'rejadx-server-all.jar');

  const serverOptions: ServerOptions = {
    command: 'java',
    args: ['-jar', serverJar],
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
  await client.start();
}

export interface ReJadxClientSettings {
  searchMaxResults: number;
  customJadxArgs: string;
  enableExternalPlugins: boolean;
  enableCodeCache: boolean;
}

export function getReJadxSettings(): ReJadxClientSettings {
  const cfg = vscode.workspace.getConfiguration('rejadx');
  const searchMaxResults = cfg.get<number>('search.maxResults', 50);
  const customJadxArgs = cfg.get<string>('customJadxArgs', '');
  const enableExternalPlugins = cfg.get<boolean>('enableExternalPlugins', false);
  const enableCodeCache = cfg.get<boolean>('enableCodeCache', true);
  return { searchMaxResults, customJadxArgs, enableExternalPlugins, enableCodeCache };
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
