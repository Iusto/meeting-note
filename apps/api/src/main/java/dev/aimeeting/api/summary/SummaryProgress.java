package dev.aimeeting.api.summary;

public record SummaryProgress(Stage stage, int percent, int completedChunks, int totalChunks) {
    public enum Stage { IDLE, PREPARING, CHUNKS, SYNTHESIS, SAVING, COMPLETED }

    public static final SummaryProgress IDLE = new SummaryProgress(Stage.IDLE, 0, 0, 0);
    public static final SummaryProgress PREPARING = new SummaryProgress(Stage.PREPARING, 0, 0, 0);
}
