package com.wolfmind.application.room;

/** Live authenticated subscriptions; this does not change room membership. */
public interface RoomPresencePort {
    boolean present(String roomId, String gameId, String userId);
}
