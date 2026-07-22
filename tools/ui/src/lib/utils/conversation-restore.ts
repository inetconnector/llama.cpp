export function resolveRestorableConversationId(
	persistedId: string | null,
	conversationIds: string[]
): string | null {
	if (persistedId && conversationIds.includes(persistedId)) {
		return persistedId;
	}
	return conversationIds[0] ?? null;
}
