# Per-game compatibility notes

## GameNative ports

- [Steam launch arguments (#2408)](https://github.com/utkarshdalal/GameNative/pull/2408): ColdClient and Bionic Steam pass the arguments only to the matching executable. This includes OMORI's Steam launch entry.
- [Stellar Blade PSPC SDK (#2384)](https://github.com/utkarshdalal/GameNative/pull/2384): when Steam's depot metadata does not expose the SDK step, Gameplay checks the game's own `SB/Binaries/Win64/installscript.vdf` for the known installer and its `quiet` argument. The fallback does not execute other steps from that file.
- [Half-Life 2 and Half-Life 2: VR Mod](https://github.com/utkarshdalal/GameNative/tree/master/app/src/main/java/app/gamenative/gamefixes): Half-Life 2 adds `-game hl2_complete` only when there are no custom arguments. The VR Mod appends `VK_EXT_fragment_density_map` to the Vulkan extension blacklist without removing existing entries.

## Optional DX12 settings from DroidDeck

These are per-game settings to try on compatible Adreno hardware with a current Turnip and VKD3D-Proton. They are not global defaults and are not validated on Gameplay hardware. Revert them if the game regresses. Do not force them on Mali or other GPUs without confirming support.

| Game | Settings to try | Upstream evidence |
| --- | --- | --- |
| Final Fantasy VII Rebirth | Set the VKD3D feature level to `12_2` in the game's container graphics settings. | [DroidDeck #2289](https://github.com/Droid-Deck/DroidDeck/pull/2289) |
| Kingdom Come: Deliverance II | Set the VKD3D feature level to `12_2` and set `VKD3D_SHADER_MODEL=6_9` in the game's environment variables. | [DroidDeck #2787](https://github.com/Droid-Deck/DroidDeck/pull/2787); the PR did not verify it on a device. |

Winlator's fixes for Half-Life and Digimon Story: Cyber Sleuth are part of [Gladio in Winlator 11.2](https://github.com/brunodev85/winlator/releases/tag/v11.2.0), not standalone game settings. Gameplay does not apply these renderer fixes.
