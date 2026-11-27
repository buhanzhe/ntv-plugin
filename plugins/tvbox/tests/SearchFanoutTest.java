package xiao.bu.channel.tvbox;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.nio.file.*;
import java.io.*;

public final class SearchFanoutTest {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        String series = BoxSearchTitle.normalize("锦墨风华：穿越后我踹了太子");
        check(BoxSearchTitle.rank(series, "锦墨风华 : 穿越后我踹了太子") == 0, "Exact title missed an alternate separator");
        check(BoxSearchTitle.rank(series, "锦墨风华：穿越后我踹了太子 第 1 集") == 1, "Title prefix is not preferred");
        check(BoxSearchTitle.rank(series, "短剧 · 锦墨风华：穿越后我踹了太子 · 合集") == 2, "Full title inside a label was missed");
        check(BoxSearchTitle.rank(series, "王亚民 - 穿越") == 3, "Partial keyword outranked a title match");
        check(BoxSearchTitle.rank(BoxSearchTitle.normalize("ＭＶ 2"), "mv2") == 0, "Width and case normalization");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            AtomicInteger active = new AtomicInteger(), maximum = new AtomicInteger();
            List<Integer> results = Collections.synchronizedList(new ArrayList<>());
            AtomicInteger errors = new AtomicInteger(); CountDownLatch first = new CountDownLatch(1), done = new CountDownLatch(1);
            CountDownLatch slow = new CountDownLatch(1);
            new SearchFanout<Integer, Integer>(pool, 2, Arrays.asList(0, 1, 2, 3), site -> {
                int count = active.incrementAndGet(); maximum.updateAndGet(n -> Math.max(n, count));
                try { if (site == 0) slow.await(); if (site == 2) throw new IOException("offline"); return site; }
                finally { active.decrementAndGet(); }
            }, new SearchFanout.Observer<Integer, Integer>() {
                public void result(Integer site, Integer result, Throwable error) {
                    if (error != null) errors.incrementAndGet(); else results.add(result);
                    first.countDown();
                }
                public void finished(boolean cancelled) { check(!cancelled, "Unexpected cancellation"); done.countDown(); }
            });
            check(first.await(2, TimeUnit.SECONDS), "Fast sites waited for a slow site");
            check(!results.contains(0), "Slow fixture was not blocked");slow.countDown();
            check(done.await(3, TimeUnit.SECONDS), "All sites did not finish");
            check(maximum.get() <= 2 && results.size() == 3 && errors.get() == 1, "Concurrency/results/error isolation");

            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), cancelled = new CountDownLatch(1);
            AtomicInteger late = new AtomicInteger();
            SearchFanout<Integer, Integer> task = new SearchFanout<>(pool, 1, Arrays.asList(1, 2), site -> {
                entered.countDown();try { release.await(); } catch (InterruptedException ignored) { release.await(); }return site;
            }, new SearchFanout.Observer<Integer, Integer>() {
                public void result(Integer site, Integer result, Throwable error) { late.incrementAndGet(); }
                public void finished(boolean value) { check(value, "Cancellation status");cancelled.countDown(); }
            });
            check(entered.await(2, TimeUnit.SECONDS), "Worker did not start");task.cancel();
            check(cancelled.await(1, TimeUnit.SECONDS), "Cancellation blocked on provider");release.countDown();
            pool.shutdown();check(pool.awaitTermination(3, TimeUnit.SECONDS), "Worker leaked");
            check(late.get() == 0, "Cancelled search delivered stale results");
        } finally { pool.shutdownNow(); }

        File directory = Files.createTempDirectory("tvbox-jar-cache").toFile();
        File payload = new File(directory, "sample");try (FileOutputStream out = new FileOutputStream(payload)) { out.write(new byte[]{1,2,3}); }
        String sha = SpiderJarCache.hash(payload, "SHA-256"), md5 = SpiderJarCache.hash(payload, "MD5");
        File jar = new File(directory, sha + ".jar");check(payload.renameTo(jar), "Cache fixture setup");
        SpiderJarCache.remember(directory, "https://example.org/spider", sha, "", 1000);
        check(SpiderJarCache.find(directory, "https://example.org/spider", "", 1100) != null, "Fresh cache miss");
        check(SpiderJarCache.find(directory, "https://example.org/spider", "", 700001) == null, "Expired mutable JAR reused");
        check(SpiderJarCache.find(directory, "https://example.org/spider", "", 999) == null, "Future timestamp accepted");
        SpiderJarCache.remember(directory, "pinned", sha, md5, 1000);
        check(SpiderJarCache.find(directory, "pinned", md5, 900000) != null, "Matching pinned JAR missed");
        check(SpiderJarCache.find(directory, "pinned", "00000000000000000000000000000000", 1100) == null, "Wrong pin accepted");
        try (FileOutputStream out = new FileOutputStream(jar)) { out.write(new byte[]{4,5,6}); }
        check(SpiderJarCache.find(directory, "pinned", md5, 1100) == null, "Corrupt cache reused");
        for (File file : directory.listFiles()) file.delete();directory.delete();
        String html = "<div class='play_list'><ul><li><a href='/mp3/one.html'>王力宏 - 唯一 &amp; Live</a></li>"
                + "<li><a href='/mp3/one.html'>duplicate</a></li><li><a href='https://other.example/mp3/evil.html'>foreign</a></li>"
                + "<li><a href='/singer/two.html'><b>&#x738b;力宏</b></a></li></ul></div><a href='/mp3/footer.html'>footer</a>";
        org.json.JSONArray music = MusicSearch.parse(html, "http://music.example/").getJSONArray("list");
        check(music.length() == 2, "Music results leaked footer/foreign/duplicate links");
        check(music.getJSONObject(0).getString("vod_name").equals("王力宏 - 唯一 & Live"), "Music names/entities");
        check(music.getJSONObject(1).getString("vod_id").equals("/singer/two.html"), "Music source IDs changed");
        check(music.getJSONObject(1).getString("vod_name").equals("王力宏"), "Music numeric entities");
        org.json.JSONObject song = MusicSearch.detail(music.getJSONObject(0).getString("vod_id")).getJSONArray("list").getJSONObject(0);
        check(song.getString("vod_name").equals("王力宏 - 唯一 & Live"), "Search song detail was replaced with a default playlist");
        check(song.getString("vod_play_url").endsWith("$/mp3/one.html"), "Native music player ID changed");
        check(MusicSearch.detail("/singer/two.html") == null, "Native singer playlist was intercepted");
        com.github.catvod.spider.MusicGuard lazy = new com.github.catvod.spider.MusicGuard();
        check(MusicSearch.prepare(lazy) && lazy.homeCalls == 1, "First music search missed the lazy native delegate");
        System.out.println("PASS bounded progressive search, isolated failures, cancellation and validated JAR caching");
    }
}
