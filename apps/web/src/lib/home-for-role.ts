/**
 * Which shell a freshly-authenticated user lands in. Roles are loose strings
 * since we only care about a small set of routing branches; anything unknown
 * falls through to the admin shell (which has its own role guards).
 *
 * Shared by the password login and the Telegram accept flow so both send the
 * same role to the same place.
 */
export type HomeShell = "admin" | "parent" | "student";

export function homeForRole(
  role: string | null | undefined,
  platformRole: string | null | undefined,
): HomeShell {
  if (platformRole === "PLATFORM_ADMIN") return "admin";
  switch (role) {
    case "PARENT":
      return "parent";
    case "STUDENT":
      return "student";
    case "SCHOOL_ADMIN":
      return "admin";
    // Teachers don't have their own shell yet; they land on /admin for now
    // (which has its own role gate). Add branches here as those ship.
    default:
      return "admin";
  }
}
