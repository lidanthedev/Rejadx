import * as vscode from 'vscode';

export type ClassTreeItemKind = 'package' | 'class';

export class ClassTreeItem extends vscode.TreeItem {
  constructor(
    public readonly label: string,
    public readonly kind: ClassTreeItemKind,
    public readonly fullName: string,
    collapsibleState: vscode.TreeItemCollapsibleState
  ) {
    super(label, collapsibleState);
    this.tooltip = fullName;
    this.contextValue = kind;
    if (kind === 'class') {
      this.command = {
        command: 'vscode.open',
        title: 'Open Class',
        // Opens as a virtual jadx:// document — never writes to disk.
        arguments: [vscode.Uri.parse(`jadx://class/${encodeURIComponent(fullName)}`)],
      };
    }
  }
}

export class ClassTreeProvider implements vscode.TreeDataProvider<ClassTreeItem> {
  private readonly _onDidChangeTreeData = new vscode.EventEmitter<ClassTreeItem | undefined | void>();
  readonly onDidChangeTreeData = this._onDidChangeTreeData.event;

  private _roots: ClassTreeItem[] = [];

  getTreeItem(element: ClassTreeItem): vscode.TreeItem {
    return element;
  }

  getChildren(element?: ClassTreeItem): ClassTreeItem[] {
    // Top-level returns roots; children populated via setRoots() from LSP responses.
    if (!element) {
      return this._roots;
    }
    return [];
  }

  setRoots(items: ClassTreeItem[]): void {
    this._roots = items;
    this._onDidChangeTreeData.fire();
  }

  refresh(): void {
    this._onDidChangeTreeData.fire();
  }
}
