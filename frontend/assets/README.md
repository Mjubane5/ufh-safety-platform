# Frontend assets

## The UFH logo

`ufh-logo.svg` is the official University of Fort Hare crest, fetched
directly from `https://www.ufh.ac.za` (the site's own header logo asset) -
not redrawn or approximated. Every page's `.brand-logo` `<img>` and
`<link rel="icon">` favicon reference it.

If Comms ever supplies an updated logo pack or corporate identity manual,
replace this file in place (same filename, same viewBox aspect ratio so
`.brand-logo`'s sizing in `css/styles.css` doesn't need to change) and check
whether the manual's official hex values should replace the approximations
in the brand colour block at the top of `css/styles.css`.

## Licensing note for the report

The logo belongs to the university. This is coursework submitted to the
university, so using it is fine, but say in the report that the mark is the
institution's and is not covered by whatever licence we put on our own code.
