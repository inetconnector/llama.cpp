<script lang="ts">
	import { Mic, Square } from '@lucide/svelte';
	import { Button } from '$lib/components/ui/button';
	import { t } from '$lib/i18n';

	interface Props {
		class?: string;
		disabled?: boolean;
		isLoading?: boolean;
		isRecording?: boolean;
		onMicClick?: () => void;
	}

	let {
		class: className = '',
		disabled = false,
		isLoading = false,
		isRecording = false,
		onMicClick
	}: Props = $props();
</script>

<div class="flex items-center gap-1 {className}">
	<Button
		class="h-8 w-8 rounded-full p-0 {isRecording
			? 'animate-pulse bg-red-500 text-white hover:bg-red-600'
			: 'bg-cyan-500 text-white hover:bg-cyan-600 dark:bg-cyan-500 dark:text-white dark:hover:bg-cyan-400'}"
		disabled={disabled || isLoading}
		onclick={onMicClick}
		title={isRecording ? t('Stop recording') : t('Start recording')}
		type="button"
	>
		<span class="sr-only">{isRecording ? t('Stop recording') : t('Start recording')}</span>

		{#if isRecording}
			<Square class="h-4 w-4 animate-pulse fill-white" />
		{:else}
			<Mic class="h-4 w-4 text-white" />
		{/if}
	</Button>
</div>
