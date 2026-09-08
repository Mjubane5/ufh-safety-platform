# Frontend assets

## The UFH logo

Put the official University of Fort Hare logo in this folder as
`ufh-logo.svg` (preferred) or `ufh-logo.png`, then in each page swap the
placeholder monogram for the image:

```html
<!-- replace this -->
<span class="brand-mark" aria-hidden="true">UFH</span>

<!-- with this -->
<img class="brand-logo" src="./assets/ufh-logo.svg" alt="University of Fort Hare">
```

`.brand-logo` is already styled in `css/styles.css`, so nothing else changes.

Until then every page shows a gold "UFH" monogram. Nobody has drawn an
imitation of the coat of arms and nobody should - it is the university's
registered mark and an approximation would be wrong in the report and wrong
on screen.

Ask Comms for the logo pack and the corporate identity manual at the same
time. The manual has the official hex values, which need to replace the
approximations in the brand block at the top of `css/styles.css`.

## Licensing note for the report

The logo belongs to the university. This is coursework submitted to the
university, so using it is fine, but say in the report that the mark is the
institution's and is not covered by whatever licence we put on our own code.
