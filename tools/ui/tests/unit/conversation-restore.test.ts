import { describe, expect, it } from 'vitest';
import { resolveRestorableConversationId } from '$lib/utils/conversation-restore';

describe('resolveRestorableConversationId', () => {
	it('restores the last opened conversation when it still exists', () => {
		expect(resolveRestorableConversationId('last-opened', ['newest', 'last-opened'])).toBe(
			'last-opened'
		);
	});

	it('falls back to the newest stored conversation for a stale marker', () => {
		expect(resolveRestorableConversationId('deleted-chat', ['newest', 'older'])).toBe('newest');
	});

	it('returns null when there are no stored conversations', () => {
		expect(resolveRestorableConversationId(null, [])).toBeNull();
	});
});
