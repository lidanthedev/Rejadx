import * as vscode from 'vscode';

export interface PackageNode {
  name: string;
  fullName: string;
  uri: string | null;
  isPackage: boolean;
  children: PackageNode[];
}

class ClassTreeItem extends vscode.TreeItem {
  constructor(public readonly node: PackageNode) {
    super(
      node.name,
      node.children.length > 0
        ? vscode.TreeItemCollapsibleState.Collapsed
        : vscode.TreeItemCollapsibleState.None
    );
    this.tooltip = node.fullName;
    this.contextValue = node.isPackage ? 'package' : 'class';
    if (!node.isPackage && node.uri) {
      this.command = {
        command: 'vscode.open',
        title: 'Open Class',
        arguments: [vscode.Uri.parse(node.uri)],
      };
    }
  }
}

export class ClassTreeProvider implements vscode.TreeDataProvider<ClassTreeItem> {
  private readonly _onDidChangeTreeData = new vscode.EventEmitter<ClassTreeItem | undefined | void>();
  readonly onDidChangeTreeData = this._onDidChangeTreeData.event;

  private _roots: PackageNode[] = [];

  getTreeItem(element: ClassTreeItem): vscode.TreeItem {
    return element;
  }

  getChildren(element?: ClassTreeItem): ClassTreeItem[] {
    const nodes = element ? element.node.children : this._roots;
    return nodes.map(n => new ClassTreeItem(n));
  }

  setRoots(roots: PackageNode[]): void {
    this._roots = roots;
    this._onDidChangeTreeData.fire();
  }

  refresh(): void {
    this._onDidChangeTreeData.fire();
  }
}
