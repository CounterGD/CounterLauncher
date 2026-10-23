package me.countergd.launcher.model;

public record GameVersion(String id, String type, String url, String releaseTime) {
    @Override
    public String toString() {
        return id + "  [" + type + "]";
    }
}
