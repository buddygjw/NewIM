package com.whosbean.newim.chatter.exchange;

import com.whosbean.newim.chatter.RouterServerNode;
import com.whosbean.newim.zookeeper.ZKPaths;
import org.apache.curator.framework.api.CuratorWatcher;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.Charset;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;

/**
 * http://blog.sina.com.cn/s/blog_616e189f01018axz.html
 * Created by yaming_deng on 14-9-9.
 */
@Component
public class ExchangeClientManager implements InitializingBean, DisposableBean {

    protected Logger logger = LoggerFactory.getLogger(this.getClass());

    public static ExchangeClientManager instance;

    @Autowired
    private RouterServerNode routerServerNode;

    private ConcurrentSkipListSet<String> servers = new ConcurrentSkipListSet<String>();

    private ConcurrentHashMap<String, ExchangeClient> clients = new ConcurrentHashMap<String, ExchangeClient>();

    @Override
    public void destroy() throws Exception {
        Iterator<String> itor = clients.keySet().iterator();
        while (itor.hasNext()){
            String name = itor.next();
            ExchangeClient client = clients.get(name);
            if (client != null){
                client.stop();
            }
        }
    }

    public class ServerNodeWatcher implements CuratorWatcher {

        private final String path;

        public String getPath() {
            return path;
        }

        public ServerNodeWatcher(String path) {
            this.path = path;
        }

        @Override
        public void process(WatchedEvent event) throws Exception {
            logger.info("process Event: {}", event);
            if(event.getType() == Watcher.Event.EventType.NodeDataChanged){
                byte[] data = null;
                System.out.println(path+":"+new String(data, Charset.forName("utf-8")));
            }else if(event.getType() == Watcher.Event.EventType.NodeChildrenChanged){
                List<String> list = routerServerNode.getExchangeServer();
                //compare
                for (String item : list){
                    if (servers.contains(item)){
                        continue;
                    }
                    logger.info("New Server Found. " + item);
                    newExchangeClient(item);
                }
                for (String item : servers){
                    if (list.contains(item)){
                        continue;
                    }
                    logger.info("Server was Removed. " + item);
                    removeExchangeClient(item);
                }
                servers.clear();
                servers.addAll(list);
            }
        }

    }

    public class ListenThread extends Thread{

        @Override
        public void run() {
            Stat stat = null;
            while (stat == null){
                try {
                    String path = ZKPaths.NS_ROOT + ZKPaths.PATH_SERVERS;
                    stat = routerServerNode.getZkClient()
                            .checkExists()
                            .usingWatcher(new ServerNodeWatcher(path))
                            .forPath(path);
                    if (stat != null){
                        List<String> list = routerServerNode.getExchangeServer();
                        for (String host : list){
                            newExchangeClient(host);
                        }
                        servers.addAll(list);
                        break;
                    }else{
                        logger.info("wait for path. " + path);
                        Thread.sleep(1 * 1000);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    protected void newExchangeClient(String host){
        String[] temp = host.split(":");
        ExchangeClient client = new ExchangeClient(temp[0], Integer.parseInt(temp[1]));
        client.start();
        logger.info("newExchangeClient. host=" + host);
        this.clients.put(host, client);
    }

    protected void removeExchangeClient(String host){
        ExchangeClient client = this.clients.get(host);
        if (client != null) {
            client.setEnabled(false);
        }
        this.clients.remove(host);
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        instance = this;
        new ListenThread().start();
        logger.info("ExchangeClientManager start.");
    }

    public ExchangeClient find(String host){
        ExchangeClient client = this.clients.get(host);
        return client;
    }

}
