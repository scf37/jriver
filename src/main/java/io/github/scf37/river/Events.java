package io.github.scf37.river;

import io.github.scf37.jpaq.Event;
import java.util.Locale;

/** What the archiver reports, as text for the user. */
final class Events {
  private Events() {}

  /** One line describing the event, null if it is not worth showing. */
  static String text(Event event) {
    return switch (event) {
      // problems
      case Event.ArchiveNotFound e -> e.name() + " not found";
      case Event.BlockSkipped e -> "skipping block at " + e.offset() + ": " + e.reason();
      case Event.FileError e -> e.name() + ": " + e.reason();
      case Event.Diagnostic e -> e.message().strip();
      case Event.DateAdjusted e -> "adjusting date from " + e.from() + " to " + e.to();
      case Event.BadFragments e -> e.name() + ": bad fragment IDs, skipped";
      case Event.ExtractFailed e ->
          e.name() + ": failed, " + e.fragmentsExtracted() + " of " + e.fragmentsTotal() + " fragments extracted";
      case Event.ExtractFailures e ->
          "extracted " + (e.files() - e.failed()) + " of " + e.files() + " files OK (" + e.failed() + " errors)";
      // progress
      case Event.ArchiveRead e -> f("%d versions, %d files, %d fragments, %.6f MB", e.versions(), e.files(),
          e.fragments(), e.bytes() / 1e6);
      case Event.AddPlan e -> f("adding %.6f MB in %d files -method %s", e.bytes() / 1e6, e.files(), e.method());
      case Event.FileAdded e -> (e.updated() ? "# " : "+ ") + e.name();
      case Event.FileRemoved e -> "- " + e.name();
      case Event.AddSummary e -> e.added() + " +added, " + e.removed() + " -removed";
      case Event.AddTotals e -> f("%d -> %d bytes, archive %d bytes", e.inputBytes(), e.addedBytes(), e.archiveBytes());
      case Event.ExtractPlan e -> f("extracting %.6f MB in %d files", e.bytes() / 1e6, e.files());
      case Event.FileExtracted e -> "> " + e.name();
      case Event.SkippedSummary e -> e.count() + (e.identical() ? " identical" : " existing") + " files skipped";
      case Event.IndexWritten e -> e.name() + " -> " + e.bytes();
      case Event.Note e -> e.message().strip();
      default -> null;
    };
  }

  private static String f(String format, Object... args) {
    return String.format(Locale.ROOT, format, args);
  }
}
