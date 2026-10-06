"use client";

import type { ReactNode } from "react";
import type {
  BucketGroup,
  TemporalBucket,
} from "@/lib/class-detail-buckets";

/**
 * Generic renderer for a list of {@link BucketGroup}s. Each non-past bucket
 * gets a {@code <section>} with a heading + count; the "past" bucket is
 * wrapped in {@code <details>} so overdue / out-of-window rows stay one
 * click away without taking scroll space at the top of the panel.
 *
 * <p>Shared between Homework and Lessons because both render an
 * unordered list of cards using the same {@code .hw-list} primitive.
 * Schedule has its own week-grain grouping so it doesn't use this
 * helper directly — but it shares the bucket label translations
 * ({@code admin.classes.buckets.label.*}) for parity across the page.
 *
 * <p>The {@code listClass} prop lets each panel keep its own list
 * className modifier (e.g. {@code .hw-list--bucket}); default is the
 * stock {@code .hw-list}.
 */
export function BucketedList<T extends { id: string }>({
  groups,
  tBuckets,
  renderRow,
  listClass = "hw-list hw-list--bucket",
}: {
  groups: BucketGroup<T>[];
  tBuckets: (
    key: string,
    values?: Record<string, string | number | Date>,
  ) => string;
  renderRow: (item: T) => ReactNode;
  listClass?: string;
}) {
  if (groups.length === 0) return null;
  return (
    <div className="hw-buckets">
      {groups.map((g) => {
        const heading = tBuckets(`label.${g.bucket}`);
        const count = g.items.length;
        if (g.bucket === "past") {
          return (
            <details key={g.bucket} className="hw-bucket hw-bucket--past">
              <summary className="hw-bucket__summary">
                <span className="hw-bucket__heading">{heading}</span>
                <span className="hw-bucket__count">{count}</span>
              </summary>
              <ul className={listClass}>{g.items.map(renderRow)}</ul>
            </details>
          );
        }
        return (
          <section
            key={g.bucket}
            className={`hw-bucket hw-bucket--${bucketSlug(g.bucket)}`}
          >
            <header className="hw-bucket__header">
              <h3 className="hw-bucket__heading">{heading}</h3>
              <span className="hw-bucket__count">{count}</span>
            </header>
            <ul className={listClass}>{g.items.map(renderRow)}</ul>
          </section>
        );
      })}
    </div>
  );
}

function bucketSlug(bucket: TemporalBucket): string {
  switch (bucket) {
    case "thisWeek":
      return "this-week";
    case "nextWeek":
      return "next-week";
    case "later":
      return "later";
    case "noDate":
      return "no-date";
    case "past":
      return "past";
  }
}
