# Implementation Plan - Fix SokujaProvider.kt

The Sokuja website has migrated to a Next.js architecture, which broke the existing scraper logic (which was based on the old WordPress theme). This plan updates the provider to support the new structure, fixes missing images, corrects episode sorting, and restores video playback.

## User Review Required

> [!IMPORTANT]
> The site now uses a different URL structure and streams data in serialized JSON chunks. The fix involves parsing these JSON chunks to extract metadata.

## Proposed Changes

### SokujaProvider.kt

Update the provider to handle the new Next.js based theme.

#### [MODIFY] [SokujaProvider.kt](file:///D:/PROGRAM/repo/REPOSITORI-ANSRIZAL/SokujaProvider/src/main/kotlin/com/ansrizal/anime/SokujaProvider.kt)
- **Image Fix**: Add support for `/_next/image/` paths and extract the original URL from the `url` parameter to avoid blurred/placeholder images.
- **Episode Fix**: Implement a Regex-based parser to extract episode data from the `self.__next_f.push` script tags. This provides reliable `episodeNumber` and slugs, ensuring episodes are sorted correctly (ascending from 1).
- **Playback Fix**: Use the newly discovered mirror API (`/api/video-mirrors?e=[id]`) to fetch direct video links instead of searching for iframes in the DOM (which are now rendered client-side).
- **Selector Update**: Update CSS selectors in `getMainPage` and `search` to match the new `a.group.block` structure.
- **Header Update**: Add `Referer` and proper `User-Agent` to all requests to avoid being blocked by the server.

## Verification Plan

### Manual Verification
- Deploy the updated provider.
- Verify that anime posters appear in the "Update Terbaru" and "Daftar Anime" sections.
- Open an anime (e.g., Tomb Raider King) and verify that episodes are listed in order (1, 2, 3...).
- Click an episode and verify that video links are found and can be played.
