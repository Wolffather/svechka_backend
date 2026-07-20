package com.svechka.backend.diary;

import java.util.UUID;

public record FollowUpResponse(UUID entryId, String summary, String status) {
}
