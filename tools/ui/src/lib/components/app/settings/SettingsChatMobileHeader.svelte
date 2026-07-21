<script lang="ts">
	import { goto } from '$app/navigation';
	import { Settings, ChevronLeft, ChevronRight } from '@lucide/svelte';
	import { onMount, tick } from 'svelte';
	import type { SettingsSection, SettingsSectionTitle } from '$lib/constants';
	import { useScrollCarousel } from '$lib/hooks/use-scroll-carousel.svelte';

	interface Props {
		sections: SettingsSection[];
		isActive: (section: SettingsSection) => boolean;
		getHref?: (section: SettingsSection) => string;
		onSectionChange?: (section: SettingsSectionTitle) => void;
	}

	let { sections, isActive, getHref, onSectionChange }: Props = $props();

	const carousel = useScrollCarousel();
	let swipeStart: { x: number; y: number; pointerId: number } | null = null;

	onMount(async () => {
		await tick();
		if (carousel.scrollContainer) {
			const activeTab = carousel.scrollContainer.querySelector('[data-active="true"]');
			if (activeTab instanceof HTMLElement) {
				carousel.scrollToCenter(activeTab);
			}
		}
	});

	$effect(() => {
		if (!carousel.scrollContainer) return;

		const activeTab = carousel.scrollContainer.querySelector('[data-active="true"]');
		if (activeTab instanceof HTMLElement) {
			void tick().then(() => {
				carousel.scrollToCenter(activeTab);
			});
		}
	});

	export function updateCarousel() {
		setTimeout(carousel.updateScrollButtons, 100);
	}

	function getActiveSectionIndex() {
		return sections.findIndex((section) => isActive(section));
	}

	function navigateToAdjacentSection(direction: -1 | 1) {
		const activeIndex = getActiveSectionIndex();
		if (activeIndex < 0) return;

		const nextSection = sections[activeIndex + direction];
		if (!nextSection) return;

		if (getHref) {
			void goto(getHref(nextSection));
			return;
		}

		onSectionChange?.(nextSection.title);
	}

	function isInteractiveSwipeTarget(target: EventTarget | null) {
		return (
			target instanceof Element &&
			Boolean(
				target.closest(
					'input, textarea, select, button, a, [role="button"], [contenteditable="true"]'
				)
			)
		);
	}

	function handleSwipePointerDown(event: PointerEvent) {
		if (event.pointerType !== 'touch') return;
		if (isInteractiveSwipeTarget(event.target)) return;

		swipeStart = {
			x: event.clientX,
			y: event.clientY,
			pointerId: event.pointerId
		};
	}

	function handleSwipePointerUp(event: PointerEvent) {
		if (!swipeStart || event.pointerId !== swipeStart.pointerId) return;

		const dx = event.clientX - swipeStart.x;
		const dy = event.clientY - swipeStart.y;
		swipeStart = null;

		if (Math.abs(dx) < 64) return;
		if (Math.abs(dx) < Math.abs(dy) * 1.2) return;

		navigateToAdjacentSection(dx < 0 ? 1 : -1);
	}

	function handleSwipePointerCancel() {
		swipeStart = null;
	}
</script>

<div class="sticky top-0 z-10 flex flex-col bg-background md:hidden">
	<div class="flex items-center gap-2 px-4 pt-4 pb-2 md:pt-6">
		<Settings class="h-5 w-5 md:h-6 md:w-6" />

		<h1 class="text-xl font-semibold md:text-2xl">Settings</h1>
	</div>

	<div
		class="border-b border-border/30 py-2"
		onpointerdown={handleSwipePointerDown}
		onpointerup={handleSwipePointerUp}
		onpointercancel={handleSwipePointerCancel}
	>
		<div class="relative flex items-center" style="scroll-padding: 1rem;">
			<button
				class="absolute left-2 z-10 flex h-6 w-6 items-center justify-center rounded-full bg-muted shadow-md backdrop-blur-sm transition-opacity hover:bg-accent {carousel.canScrollLeft
					? 'opacity-100'
					: 'pointer-events-none opacity-0'}"
				onclick={carousel.scrollLeft}
				aria-label="Scroll left"
			>
				<ChevronLeft class="h-4 w-4" />
			</button>

			<div
				class="scrollbar-hide overflow-x-auto py-2"
				bind:this={carousel.scrollContainer}
				onscroll={carousel.updateScrollButtons}
			>
				<div class="flex min-w-max gap-2">
					{#each sections as section (section.title)}
						{#if getHref}
							<a
								class="flex cursor-pointer items-center gap-2 rounded-lg px-3 py-2 text-sm whitespace-nowrap no-underline transition-colors first:ml-4 last:mr-4 hover:bg-accent {isActive(
									section
								)
									? 'bg-accent text-accent-foreground'
									: 'text-muted-foreground'}"
								data-active={isActive(section)}
								href={getHref(section)}
								onclick={(e: MouseEvent) => {
									carousel.scrollToCenter(e.currentTarget as HTMLElement);
								}}
							>
								<section.icon class="h-4 w-4 flex-shrink-0" />
								<span>{section.title}</span>
							</a>
						{:else}
							<button
								class="flex cursor-pointer items-center gap-2 rounded-lg px-3 py-2 text-sm whitespace-nowrap transition-colors first:ml-4 last:mr-4 hover:bg-accent {isActive(
									section
								)
									? 'bg-accent text-accent-foreground'
									: 'text-muted-foreground'}"
								data-active={isActive(section)}
								onclick={(e: MouseEvent) => {
									onSectionChange?.(section.title);
									carousel.scrollToCenter(e.currentTarget as HTMLElement);
								}}
							>
								<section.icon class="h-4 w-4 flex-shrink-0" />
								<span>{section.title}</span>
							</button>
						{/if}
					{/each}
				</div>
			</div>

			<button
				class="absolute right-2 z-10 flex h-6 w-6 items-center justify-center rounded-full bg-muted shadow-md backdrop-blur-sm transition-opacity hover:bg-accent {carousel.canScrollRight
					? 'opacity-100'
					: 'pointer-events-none opacity-0'}"
				onclick={carousel.scrollRight}
				aria-label="Scroll right"
			>
				<ChevronRight class="h-4 w-4" />
			</button>
		</div>
	</div>
</div>
