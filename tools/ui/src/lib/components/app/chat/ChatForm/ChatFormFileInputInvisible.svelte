<script lang="ts">
	interface Props {
		class?: string;
		accept?: string;
		capture?: string;
		multiple?: boolean;
		onFileSelect?: (files: File[]) => void;
	}

	let { class: className = '', accept, capture, multiple = true, onFileSelect }: Props = $props();

	let fileInputElement: HTMLInputElement | undefined;

	export function click() {
		fileInputElement?.click();
	}

	function handleFileSelect(event: Event) {
		const input = event.target as HTMLInputElement;
		const files = Array.from(input.files ?? []);

		if (files.length > 0) {
			onFileSelect?.(files);
		}

		input.value = '';
	}
</script>

<input
	bind:this={fileInputElement}
	type="file"
	{accept}
	{capture}
	{multiple}
	onchange={handleFileSelect}
	class="hidden {className}"
/>
