# babashka can do this

A native GTK4 app, no JVM, no build step

---

## What you are looking at

- Markdown on the left, **slides** on the right
- Rendered by `libadwaita`, driven by `babashka.ffi`
- The whole presenter is one file
- Keys: arrows, space, *F5*, Escape

---

> The animation is declarative. The view sets a page number; the framework
> turns that into a scroll.

---

## Why it stays small

- No JVM, so startup is instant
- `GSK_RENDERER=cairo` saves about 47 MB
- `-Xmx96m` saves 30 more
- An idle window costs 0.00% of a core

---

## Try editing me

Save this file while the deck is open and the slides reload, keeping your place.
