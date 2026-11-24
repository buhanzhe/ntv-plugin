package com.github.catvod.spider;
import com.github.catvod.crawler.Spider;
/** A wrapper whose delegate does not exist until homeContent, like the real guard. */
public class MusicGuard extends Spider {
    private Spider delegate;
    public int homeCalls;
    @Override public String homeContent(boolean filter) {
        homeCalls++;
        if (delegate == null) delegate = new Music();
        return delegate.homeContent(filter);
    }
}
