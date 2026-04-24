// Activity-bar TreeView for the ai-bench Copilot Bridge. Renders a
// compact panel users can pin in the left rail with the bridge icon —
// quick access to start/stop controls + stats dashboard.

import * as vscode from 'vscode';
import { UsageTracker } from './usage';

export interface BridgeRuntime {
    bridgeRunning: boolean;
    openAiRunning: boolean;
    socketPath: string;
    openAiUrl: string | null;
}

export class BridgeTreeProvider implements vscode.TreeDataProvider<TreeNode> {
    private readonly _onChange = new vscode.EventEmitter<TreeNode | undefined>();
    readonly onDidChangeTreeData = this._onChange.event;

    constructor(
        private readonly tracker: UsageTracker,
        private readonly getRuntime: () => BridgeRuntime
    ) {
        // Re-render the tree whenever a chat call lands so totals and
        // last-seen times stay current.
        tracker.onChange(() => this.refresh());
    }

    refresh(): void { this._onChange.fire(undefined); }

    getTreeItem(node: TreeNode): vscode.TreeItem { return node.toItem(); }

    getChildren(node?: TreeNode): TreeNode[] {
        if (!node) return this.rootNodes();
        return node.children ?? [];
    }

    private rootNodes(): TreeNode[] {
        const rt = this.getRuntime();
        const stats = this.tracker.snapshot();
        return [
            new TreeNode({
                label: 'Local IPC bridge',
                description: rt.bridgeRunning ? 'running' : 'stopped',
                contextValue: rt.bridgeRunning ? 'bridge.on' : 'bridge.off',
                icon: rt.bridgeRunning ? 'pass-filled' : 'circle-large-outline',
                tooltip: 'Socket: ' + rt.socketPath,
                children: [
                    actionNode(rt.bridgeRunning ? 'Stop bridge' : 'Start bridge',
                        rt.bridgeRunning
                            ? 'aiBench.copilotBridge.stop'
                            : 'aiBench.copilotBridge.start',
                        rt.bridgeRunning ? 'debug-stop' : 'play'),
                    infoNode('Socket', rt.socketPath, 'symbol-file'),
                ],
            }),
            new TreeNode({
                label: 'Local OpenAI endpoint',
                description: rt.openAiRunning ? 'running' : 'stopped',
                contextValue: rt.openAiRunning ? 'openai.on' : 'openai.off',
                icon: rt.openAiRunning ? 'pass-filled' : 'circle-large-outline',
                tooltip: rt.openAiUrl ?? 'Stopped',
                children: [
                    actionNode(rt.openAiRunning ? 'Stop endpoint' : 'Start endpoint',
                        rt.openAiRunning
                            ? 'aiBench.copilotBridge.stopOpenAiEndpoint'
                            : 'aiBench.copilotBridge.startOpenAiEndpoint',
                        rt.openAiRunning ? 'debug-stop' : 'play'),
                    rt.openAiUrl
                        ? infoNode('URL', rt.openAiUrl, 'globe')
                        : infoNode('URL', '— not running —', 'globe'),
                ],
            }),
            new TreeNode({
                label: 'Usage',
                description: `${stats.totalRequests} req · $${stats.totalEstimatedCostUsd.toFixed(6)}`,
                icon: 'graph',
                children: [
                    actionNode('Open stats dashboard', 'aiBench.copilotBridge.openStats', 'open-preview'),
                    actionNode('Clear stats', 'aiBench.copilotBridge.clearStats', 'trash'),
                    infoNode('Prompt tokens', stats.totalPromptTokens.toLocaleString(), 'arrow-up'),
                    infoNode('Completion tokens', stats.totalCompletionTokens.toLocaleString(), 'arrow-down'),
                ],
            }),
        ];
    }
}

interface NodeOpts {
    label: string;
    description?: string;
    tooltip?: string;
    icon?: string;
    contextValue?: string;
    command?: vscode.Command;
    children?: TreeNode[];
}

class TreeNode {
    constructor(public readonly opts: NodeOpts) {}
    get children(): TreeNode[] | undefined { return this.opts.children; }

    toItem(): vscode.TreeItem {
        const item = new vscode.TreeItem(
            this.opts.label,
            this.opts.children?.length
                ? vscode.TreeItemCollapsibleState.Expanded
                : vscode.TreeItemCollapsibleState.None
        );
        item.description = this.opts.description;
        item.tooltip = this.opts.tooltip ?? this.opts.label;
        if (this.opts.icon) item.iconPath = new vscode.ThemeIcon(this.opts.icon);
        if (this.opts.contextValue) item.contextValue = this.opts.contextValue;
        if (this.opts.command) item.command = this.opts.command;
        return item;
    }
}

function actionNode(label: string, commandId: string, icon: string): TreeNode {
    return new TreeNode({
        label, icon,
        command: { command: commandId, title: label },
    });
}

function infoNode(label: string, value: string, icon: string): TreeNode {
    return new TreeNode({ label, description: value, icon });
}
