import { ReasoningEffort } from '$lib/enums';
import { t } from '$lib/i18n';
import type { ReasoningEffortLevel } from '$lib/types';

/**
 * Reasoning effort UI labels.
 * Keys match the ReasoningEffort enum values for type-safe lookups.
 */
export const REASONING_EFFORT_LABELS: Record<string, string> = {
	[ReasoningEffort.OFF]: t('off'),
	[ReasoningEffort.LOW]: t('Low'),
	[ReasoningEffort.MEDIUM]: t('Medium'),
	[ReasoningEffort.HIGH]: t('High'),
	[ReasoningEffort.MAX]: t('Max')
};

export const REASONING_EFFORT_LEVELS: ReasoningEffortLevel[] = [
	{ value: ReasoningEffort.OFF, label: t('off'), isOff: true },
	{ value: ReasoningEffort.LOW, label: t('Low') },
	{ value: ReasoningEffort.MEDIUM, label: t('Medium') },
	{ value: ReasoningEffort.HIGH, label: t('High') },
	{ value: ReasoningEffort.MAX, label: t('Max'), hasInfo: true }
];
