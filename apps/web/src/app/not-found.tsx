import Link from "next/link";

export default function RootNotFound() {
  return (
    <html lang="en">
      <body style={{ padding: "3rem", fontFamily: "system-ui" }}>
        <h1>Not found</h1>
        <p>
          <Link href="/uz">Go home</Link>
        </p>
      </body>
    </html>
  );
}
