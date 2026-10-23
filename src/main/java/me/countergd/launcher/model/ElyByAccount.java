package me.countergd.launcher.model;

public record ElyByAccount(
        String username,
        String uuid,
        String accessToken,
        String refreshToken,
        long expiresAtEpochSeconds,
        String clientId) {

    public ElyByAccount withTokens(String access, String refresh, long expiresAt) {
        return new ElyByAccount(username, uuid, access,
                refresh == null || refresh.isBlank() ? refreshToken : refresh,
                expiresAt, clientId);
    }

    @Override public String toString() { return username + " (Ely.by)"; }
}
