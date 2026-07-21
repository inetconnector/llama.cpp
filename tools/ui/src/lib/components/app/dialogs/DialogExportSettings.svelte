<script lang="ts">
	import * as AlertDialog from '$lib/components/ui/alert-dialog';
	import { Checkbox } from '$lib/components/ui/checkbox';
	import Label from '$lib/components/ui/label/label.svelte';
	import { Shield, ShieldOff } from '@lucide/svelte';
	import { t } from '$lib/i18n';

	let {
		open = $bindable(),
		includeSensitiveData = $bindable(false),
		onCancel,
		onConfirm
	}: {
		open: boolean;
		includeSensitiveData: boolean;
		onCancel: () => void;
		onConfirm: () => void;
	} = $props();

	function handleOpenChange(newOpen: boolean) {
		if (!newOpen) {
			onCancel();
		}
	}
</script>

<AlertDialog.Root {open} onOpenChange={handleOpenChange}>
	<AlertDialog.Content>
		<AlertDialog.Header>
			<AlertDialog.Title class="flex items-center gap-2">
				{#if includeSensitiveData}
					<ShieldOff class="h-5 w-5 text-destructive" />
				{:else}
					<Shield class="h-5 w-5 text-destructive" />
				{/if}
				{t('Export Settings')}
			</AlertDialog.Title>

			<AlertDialog.Description>
				{#if includeSensitiveData}
					<p class="text-amber-500">
						{t(
							'Warning: This export will include sensitive data such as API keys and MCP server custom headers (e.g., authorization tokens). Do not share this file with anyone you do not trust.'
						)}
					</p>
				{:else}
					<p>
						{t(
							'Sensitive data (API keys, MCP server custom headers) will not be included in the export to protect your credentials.'
						)}
					</p>
				{/if}
			</AlertDialog.Description>
		</AlertDialog.Header>

		<div class="flex items-center gap-2 py-2">
			<Checkbox id="include-sensitive" bind:checked={includeSensitiveData} />

			<Label
				for="include-sensitive"
				class="text-sm leading-none peer-disabled:cursor-not-allowed peer-disabled:opacity-70"
			>
				{#if includeSensitiveData}
					<span class="text-destructive">{t('Include sensitive data (not recommended)')}</span>
				{:else}
					<span>{t('Include sensitive data')}</span>
				{/if}
			</Label>
		</div>

		<AlertDialog.Footer>
			<AlertDialog.Cancel onclick={onCancel}>{t('Cancel')}</AlertDialog.Cancel>
			<AlertDialog.Action
				onclick={onConfirm}
				class="bg-destructive text-white hover:bg-destructive/80"
			>
				{#if includeSensitiveData}
					{t('Export Anyway')}
				{:else}
					{t('Export Without Sensitive Data')}
				{/if}
			</AlertDialog.Action>
		</AlertDialog.Footer>
	</AlertDialog.Content>
</AlertDialog.Root>
