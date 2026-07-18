import { useMemo } from 'react'
import { marked } from 'marked'
import changelogRaw from '../../../CHANGELOG.md?raw'

// Repo-root CHANGELOG.md bundled at build time (?raw) — redeploying the frontend is what
// publishes a new entry, so the page can never show a changelog newer than the running build.
export function ChangelogPage() {
  const html = useMemo(() => marked.parse(changelogRaw, { async: false }), [])

  return (
    <div className="space-y-8">
      <div>
        <h1 className="text-lg font-semibold mb-1">Changelog</h1>
        <p className="text-sm text-muted-foreground">
          Strategy, engine and operations changes — source of truth is <code>CHANGELOG.md</code> in the repo root.
        </p>
      </div>
      <div
        className={[
          'max-w-3xl text-sm leading-relaxed',
          '[&_h1]:hidden', // page renders its own title
          '[&_h2]:text-base [&_h2]:font-semibold [&_h2]:mt-8 [&_h2]:mb-2 [&_h2]:border-b [&_h2]:border-border [&_h2]:pb-1',
          '[&_h3]:text-sm [&_h3]:font-semibold [&_h3]:mt-5 [&_h3]:mb-1',
          '[&_p]:my-2 [&_p]:text-muted-foreground first:[&_p]:mt-0',
          '[&_ul]:my-2 [&_ul]:pl-5 [&_ul]:list-disc [&_li]:my-1 [&_li]:text-muted-foreground',
          '[&_strong]:text-foreground [&_strong]:font-semibold',
          '[&_code]:font-mono [&_code]:text-xs [&_code]:bg-muted [&_code]:px-1 [&_code]:py-0.5 [&_code]:rounded',
        ].join(' ')}
        dangerouslySetInnerHTML={{ __html: html }}
      />
    </div>
  )
}
