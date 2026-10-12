# Vendored dependency snapshots

These two directories are ordinary tracked source directories, not submodules.
Their original submodule repositories became unavailable during the Codemagic
checkout. The exact clean local checkouts at the previously pinned commits were
imported, including their original license files. No implementation was changed.

| Directory | Original repository | Pinned commit |
| --- | --- | --- |
| `DBNumberedSlider` | `https://github.com/khanhduytran0/DBNumberedSlider` | `4eddc68b5f92247f8afc0f6114d1b92b338749f4` |
| `fishhook` | `https://github.com/khanhduytran0/fishhook` (branch `jev/main`) | `ab23646149fd8594545668754a1b7ded56a1b9af` |

The build still uses `DBNumberedSlider/Classes/DBNumberedSlider.m` and
`fishhook/fishhook.c` at the same paths. AFNetworking and MobileGlues remain
submodules. A fresh clone no longer contacts the two unavailable repositories.
