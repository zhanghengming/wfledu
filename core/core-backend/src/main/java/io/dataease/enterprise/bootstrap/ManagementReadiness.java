package io.dataease.enterprise.bootstrap;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import java.util.concurrent.atomic.AtomicBoolean;

/** The web server may listen while migrations run; only this explicit gate opens requests. */
public final class ManagementReadiness implements ApplicationListener<ApplicationReadyEvent>, Ordered {
    private final AtomicBoolean ready=new AtomicBoolean();
    public boolean ready(){return ready.get();}
    @Override public int getOrder(){return Ordered.LOWEST_PRECEDENCE;}
    @Override public void onApplicationEvent(ApplicationReadyEvent event){ready.set(true);}
}
