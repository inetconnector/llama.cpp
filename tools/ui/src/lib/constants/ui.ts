import { Search, Settings, SquarePen } from '@lucide/svelte';
import McpLogo from '$lib/components/app/mcp/McpLogo.svelte';
import type { Component } from 'svelte';
import { ROUTES } from './routes';
import { t } from '$lib/i18n';

export const FORK_TREE_DEPTH_PADDING = 8;
export const SYSTEM_MESSAGE_PLACEHOLDER = t('System message');

export const ICON_STRIP_TRANSITION_DURATION = 150;
export const ICON_STRIP_TRANSITION_DELAY_MULTIPLIER = 50;

export interface DesktopIconStripItem {
	icon: Component;
	tooltip: string;
	route?: string;
	activeRouteId?: string;
	activeRoutePrefix?: string;
	activeUrlIncludes?: string;
	keys?: string[];
}

export const SIDEBAR_ACTIONS_ITEMS: DesktopIconStripItem[] = [
	{ icon: SquarePen, tooltip: t('New chat'), route: ROUTES.NEW_CHAT, keys: ['shift', 'cmd', 'o'] },
	{ icon: Search, tooltip: t('Search'), keys: ['cmd', 'k'] },
	{
		icon: McpLogo,
		tooltip: t('MCP Servers'),
		route: ROUTES.MCP_SERVERS,
		activeRouteId: '/mcp-servers'
	},
	{
		icon: Settings,
		tooltip: t('Settings'),
		route: `${ROUTES.SETTINGS}/general`,
		activeUrlIncludes: '#/settings'
	}
];
