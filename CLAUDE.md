# Project rules: sei-nexus-ai (backend)

## Rule: keep the architecture guide in sync (definition of done)

Zevra has one standard, customer-facing explanation of how the product works: the interactive guide
**How Zevra Works**. It lives in the `sei-nexus-ui` repo at `docs/how-zevra-works/` (source in `src/`, generated
pages in `built/`). It is used in live customer demos, so it must always match the product.

**Any change to the architecture, and any new functionality, must update the guide in the same change.**
That includes changes to: tenant setup, sign-in or isolation; connections or industry packs; the discovery
pipeline; any step of the chat pipeline; any AI call (added, removed, new prompt inputs, new model default);
what is sent to OpenAI; safety checks, limits or thresholds; where data is stored; agents, tools or background
jobs; learning signals or thresholds; and any known limitation (added or closed).

How: follow `sei-nexus-ui/docs/how-zevra-works/README.md` (it maps each kind of change to the file to edit).
Edit `src/`, set `ZG.meta.reviewed`, then run `python tools/build.py`, `python tools/verify_facts.py` and
`node tools/smoke.js` (from `tools/` after `npm install`), and commit `src/` and `built/` together.

Always: read the code before writing a claim; mark illustrative examples; never state an absolute the code
does not enforce; keep "Known limits" honest. **Never publish the guide on the public docs site** (`zevra-docs`
deploys publicly on every merge); it is private by design.

When you plan or finish an architectural change or feature, state which guide sections you updated, or say
explicitly that none were affected and why.

The guide's drift check (`verify_facts.py`) reads this repository's code (model defaults, limits, thresholds,
pack counts). If you rename or reshape a class it reads, update the check in `sei-nexus-ui/docs/how-zevra-works/tools/`.
