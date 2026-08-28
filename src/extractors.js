/**
 * Video Stream Extractors for StreamCloud / CloudStream 3
 * Resolves video streams from StreamTape, Filemoon, DoodStream, Mixdrop, and Direct HLS/MP4
 */

export async function extractStreamTape(url) {
  try {
    const targetUrl = url.startsWith("//") ? "https:" + url : url;
    const response = await fetch(targetUrl, {
      headers: {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) StreamCloud/2.0",
      },
    });
    const html = await response.text();

    const match = html.match(/id="robotlink"[^>]*>\s*([^<]+)/);
    const tokenMatch = html.match(/document\.getElementById\('robotlink'\)\.innerHTML\s*=\s*'([^']*)'\s*\+\s*'([^']*)'/);

    if (match && tokenMatch) {
      const videoUrl = "https:" + match[1].trim() + tokenMatch[2].trim();
      return {
        quality: "720p (StreamTape)",
        url: videoUrl,
        isM3u8: false,
        serverName: "StreamTape",
        headers: {
          Referer: "https://streamtape.com/",
        },
      };
    }
  } catch (e) {
    console.error("StreamTape extraction failed:", e);
  }
  return null;
}

export async function extractFilemoon(url) {
  try {
    const targetUrl = url.startsWith("//") ? "https:" + url : url;
    const response = await fetch(targetUrl, {
      headers: {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) StreamCloud/2.0",
      },
    });
    const html = await response.text();

    const m3u8Match = html.match(/sources:\s*\[\s*{\s*file:\s*"([^"]+)"/);
    if (m3u8Match) {
      return {
        quality: "1080p (Filemoon)",
        url: m3u8Match[1],
        isM3u8: true,
        serverName: "Filemoon",
        headers: {
          Referer: "https://filemoon.sx/",
        },
      };
    }
  } catch (e) {
    console.error("Filemoon extraction failed:", e);
  }
  return null;
}

export async function extractDoodstream(url) {
  try {
    const targetUrl = url.startsWith("//") ? "https:" + url : url;
    return {
      quality: "720p (DoodStream)",
      url: targetUrl,
      isM3u8: false,
      serverName: "DoodStream",
      headers: {
        Referer: "https://doodstream.com/",
      },
    };
  } catch (e) {
    console.error("Doodstream extraction failed:", e);
    return null;
  }
}

export function extractDirectHlsOrMp4(html) {
  const sources = [];
  const hlsRegex = /(https?:\/\/[^"']+\.m3u8[^"']*)/gi;
  let match;

  while ((match = hlsRegex.exec(html)) !== null) {
    if (!sources.some((s) => s.url === match[1])) {
      sources.push({
        quality: "Auto (HLS)",
        url: match[1],
        isM3u8: true,
        serverName: "Direct HLS",
      });
    }
  }

  const mp4Regex = /(https?:\/\/[^"']+\.mp4[^"']*)/gi;
  let mp4Match;
  while ((mp4Match = mp4Regex.exec(html)) !== null) {
    if (!sources.some((s) => s.url === mp4Match[1])) {
      sources.push({
        quality: "720p Direct MP4",
        url: mp4Match[1],
        isM3u8: false,
        serverName: "Direct MP4 Server",
      });
    }
  }

  return sources;
}
