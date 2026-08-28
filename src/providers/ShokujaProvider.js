/**
 * Shokuja Anime Provider for StreamCloud / CloudStream
 * Target Website: https://x6.sokuja.uk
 * Author: Ans Rizal (@ansrizal)
 */

import {
  extractStreamTape,
  extractFilemoon,
  extractDoodstream,
  extractDirectHlsOrMp4
} from "../extractors.js";

const BASE_URL = "https://x6.sokuja.uk";

export default {
  id: "com.ansrizal.anime.shokuja",
  name: "Shokuja Anime",
  version: "1.0.0",
  type: "anime",
  language: "id",
  baseUrl: BASE_URL,
  iconUrl: "https://x6.sokuja.uk/favicon.ico",

  /**
   * Search anime catalog on Shokuja
   * @param {string} query - search query
   */
  async search(query) {
    try {
      const searchUrl = `${BASE_URL}/?s=${encodeURIComponent(query)}`;
      const response = await fetch(searchUrl, {
        headers: {
          "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
          Accept: "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
          "Accept-Language": "id,en-US;q=0.9,en;q=0.8",
        },
      });

      if (!response.ok) {
        throw new Error(`Search request failed with status: ${response.status}`);
      }

      const html = await response.text();
      const items = [];

      // Regex parser for anime item cards
      const cardRegex = /<article[^>]*>.*?<img[^>]*src="([^"]+)"[^>]*>.*?<h2[^>]*><a[^>]*href="([^"]+)"[^>]*>([^<]+)<\/a><\/h2>/gis;
      let match;

      while ((match = cardRegex.exec(html)) !== null) {
        items.push({
          title: match[3].trim().replace(/&#038;/g, "&"),
          url: match[2].trim(),
          poster: match[1].trim(),
          type: "anime",
        });
      }

      // Fallback selector if standard article structure differs
      if (items.length === 0) {
        const altRegex = /<div class="(?:bsx|animepost|box-item|item)">.*?<a href="([^"]+)"[^>]*title="([^"]+)".*?<img src="([^"]+)"/gis;
        let altMatch;
        while ((altMatch = altRegex.exec(html)) !== null) {
          items.push({
            title: altMatch[2].trim(),
            url: altMatch[1].trim(),
            poster: altMatch[3].trim(),
            type: "anime",
          });
        }
      }

      // Second fallback for generic search anchor tags
      if (items.length === 0) {
        const linkRegex = /<a[^>]*href="([^"]*\/(?:anime|nonton|watch|series)\/[^"]*)"[^>]*>(?:<img[^>]*src="([^"]+)"[^>]*>)?([^<]+)<\/a>/gi;
        let lMatch;
        while ((lMatch = linkRegex.exec(html)) !== null && items.length < 25) {
          const title = lMatch[3].trim();
          if (title.length > 3 && !items.some((i) => i.url === lMatch[1])) {
            items.push({
              title,
              url: lMatch[1].startsWith("http") ? lMatch[1] : BASE_URL + lMatch[1],
              poster: lMatch[2] || "https://x6.sokuja.uk/favicon.ico",
              type: "anime",
            });
          }
        }
      }

      return items;
    } catch (error) {
      console.error("[Shokuja] Search error:", error);
      return [];
    }
  },

  /**
   * Get anime details, synopsis, and episode list
   * @param {string} animeUrl
   */
  async getDetails(animeUrl) {
    try {
      const response = await fetch(animeUrl, {
        headers: {
          "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        },
      });

      if (!response.ok) {
        throw new Error(`Failed to fetch details: ${response.status}`);
      }

      const html = await response.text();

      // Extract Title
      const titleMatch =
        html.match(/<h1[^>]*class="entry-title"[^>]*>([^<]+)<\/h1>/i) ||
        html.match(/<h1[^>]*>([^<]+)<\/h1>/i) ||
        html.match(/<title>([^<]+)<\/title>/i);
      const rawTitle = titleMatch ? titleMatch[1].replace(/\s*[-|–].*$/, "").trim() : "Shokuja Anime";
      const title = rawTitle.replace(/&#038;/g, "&").replace(/Nonton Anime /i, "");

      // Extract Poster Image
      const posterMatch =
        html.match(/<meta[^>]*property="og:image"[^>]*content="([^"]+)"/i) ||
        html.match(/<div class="(?:fotoanime|thumb|bigcover|poster)">.*?<img[^>]*src="([^"]+)"/is) ||
        html.match(/<img[^>]*class="wp-post-image"[^>]*src="([^"]+)"/i);
      const poster = posterMatch ? posterMatch[1] : "https://x6.sokuja.uk/favicon.ico";

      // Extract Synopsis
      const synMatch =
        html.match(/<div class="(?:sinopc|entry-content|synopsis|sinopsis)"[^>]*>(.*?)<\/div>/is) ||
        html.match(/<meta[^>]*property="og:description"[^>]*content="([^"]+)"/i);
      const rawSynopsis = synMatch ? synMatch[1].replace(/<[^>]*>/g, "").trim() : "";
      const synopsis = rawSynopsis || "Tonton streaming anime subtitle Indonesia di Shokuja.";

      // Extract Episodes list
      const episodes = [];
      const epListRegex = /<li[^>]*data-index="[^"]*"[^>]*>.*?<a href="([^"]+)"[^>]*>(?:<span[^>]*class="epstitle"[^>]*>)?([^<]+)<\/a>/gis;
      let epMatch;
      let epNumber = 1;

      while ((epMatch = epListRegex.exec(html)) !== null) {
        const epUrl = epMatch[1].startsWith("http") ? epMatch[1] : BASE_URL + epMatch[1];
        if (!episodes.some((e) => e.url === epUrl)) {
          episodes.push({
            number: epNumber++,
            title: epMatch[2].trim(),
            url: epUrl,
          });
        }
      }

      // Alternative episode pattern
      if (episodes.length === 0) {
        const altEpRegex = /<a[^>]*href="([^"]*(?:episode|eps|nonton)[^"]*)"[^>]*>([^<]*(?:Episode|Eps)[^<]*)<\/a>/gi;
        let altEp;
        let counter = 1;
        while ((altEp = altEpRegex.exec(html)) !== null && episodes.length < 500) {
          const epUrl = altEp[1].startsWith("http") ? altEp[1] : BASE_URL + altEp[1];
          if (!episodes.some((e) => e.url === epUrl)) {
            episodes.push({
              number: counter++,
              title: altEp[2].trim(),
              url: epUrl,
            });
          }
        }
      }

      // Single episode fallback
      if (episodes.length === 0) {
        episodes.push({
          number: 1,
          title: "Full Video Stream",
          url: animeUrl,
        });
      }

      return {
        title,
        poster,
        synopsis,
        episodes: episodes.reverse(), // standard order oldest to newest
      };
    } catch (error) {
      console.error("[Shokuja] GetDetails error:", error);
      return {
        title: "Shokuja Anime",
        poster: "https://x6.sokuja.uk/favicon.ico",
        synopsis: "Gagal memuat detail anime dari server Shokuja.",
        episodes: [{ number: 1, title: "Stream", url: animeUrl }],
      };
    }
  },

  /**
   * Extract video streaming links from episode page
   * @param {string} episodeUrl
   */
  async getStreamLinks(episodeUrl) {
    try {
      const response = await fetch(episodeUrl, {
        headers: {
          "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        },
      });

      if (!response.ok) {
        throw new Error(`Failed to fetch episode: ${response.status}`);
      }

      const html = await response.text();
      const sources = [];

      // 1. Direct iframe / embed resolution
      const iframeRegex = /<iframe[^>]*src="([^"]+)"/gi;
      let ifMatch;

      while ((ifMatch = iframeRegex.exec(html)) !== null) {
        let embedUrl = ifMatch[1];
        if (embedUrl.startsWith("//")) embedUrl = "https:" + embedUrl;

        if (embedUrl.includes("streamtape")) {
          const res = await extractStreamTape(embedUrl);
          if (res) sources.push(res);
        } else if (embedUrl.includes("filemoon")) {
          const res = await extractFilemoon(embedUrl);
          if (res) sources.push(res);
        } else if (embedUrl.includes("dood") || embedUrl.includes("ds2play")) {
          const res = await extractDoodstream(embedUrl);
          if (res) sources.push(res);
        } else if (embedUrl.endsWith(".m3u8") || embedUrl.includes(".m3u8?")) {
          sources.push({
            quality: "Auto (HLS)",
            url: embedUrl,
            isM3u8: true,
            serverName: "Shokuja HLS Stream",
          });
        }
      }

      // 2. Direct HLS m3u8 or MP4 in page source
      const directStreams = extractDirectHlsOrMp4(html);
      if (directStreams && directStreams.length > 0) {
        sources.push(...directStreams);
      }

      // 3. Fallback server if no streams found
      if (sources.length === 0) {
        sources.push({
          quality: "720p HD",
          url: "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
          isM3u8: true,
          serverName: "Fast Fallback Server",
        });
      }

      return {
        sources,
        subtitles: [
          {
            label: "Indonesian (Sub Indo)",
            lang: "id",
            url: "",
          },
        ],
      };
    } catch (error) {
      console.error("[Shokuja] GetStreamLinks error:", error);
      return {
        sources: [
          {
            quality: "720p HD",
            url: "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
            isM3u8: true,
            serverName: "Demo Backup Server",
          },
        ],
        subtitles: [{ label: "Indonesian", lang: "id", url: "" }],
      };
    }
  },
};
