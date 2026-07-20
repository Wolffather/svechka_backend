package com.svechka.backend.diary;

import java.util.UUID;

public record EntryCreateResponse(UUID entryId, String followUpQuestion, String summary, String status) {
}
