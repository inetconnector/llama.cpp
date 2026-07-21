<script lang="ts">
	import * as Dialog from '$lib/components/ui/dialog';
	import { ConversationSelection } from '$lib/components/app';
	import { t } from '$lib/i18n';

	interface Props {
		conversations: DatabaseConversation[];
		messageCountMap?: Map<string, number>;
		mode: 'export' | 'import';
		onCancel: () => void;
		onConfirm: (selectedConversations: DatabaseConversation[]) => void;
		open?: boolean;
	}

	let {
		conversations,
		messageCountMap = new Map(),
		mode,
		onCancel,
		onConfirm,
		open = $bindable(false)
	}: Props = $props();

	let conversationSelectionRef: ConversationSelection | undefined = $state();

	let previousOpen = $state(false);

	$effect(() => {
		if (open && !previousOpen && conversationSelectionRef) {
			conversationSelectionRef.reset();
		} else if (!open && previousOpen) {
			onCancel();
		}

		previousOpen = open;
	});
</script>

<Dialog.Root bind:open>
	<Dialog.Portal>
		<Dialog.Overlay class="z-[1000000]" />

			<Dialog.Content class="z-[1000001] max-w-2xl">
				<Dialog.Header>
					<Dialog.Title>
					{t('Select Conversations to {{action}}', {
						action: mode === 'export' ? t('Export') : t('Import')
					})}
				</Dialog.Title>

				<Dialog.Description>
					{#if mode === 'export'}
						{t(
							'Choose which conversations you want to export. Selected conversations will be downloaded as a JSON file.'
						)}
					{:else}
						{t(
							'Choose which conversations you want to import. Selected conversations will be merged with your existing conversations.'
						)}
					{/if}
				</Dialog.Description>
			</Dialog.Header>

			<ConversationSelection
				bind:this={conversationSelectionRef}
				{conversations}
				{messageCountMap}
				{mode}
				{onCancel}
				{onConfirm}
			/>
		</Dialog.Content>
	</Dialog.Portal>
</Dialog.Root>
