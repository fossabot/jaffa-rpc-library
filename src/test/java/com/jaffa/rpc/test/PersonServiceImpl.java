package com.jaffa.rpc.test;

import com.jaffa.rpc.lib.annotations.ApiServer;
import com.jaffa.rpc.lib.common.Options;
import com.jaffa.rpc.lib.entities.RequestContext;
import com.jaffa.rpc.lib.zookeeper.Utils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@ApiServer
@Component
public class PersonServiceImpl implements PersonService {

    private final List<Person> people = new ArrayList<>();

    private final AtomicInteger idProvider = new AtomicInteger(1);

    @Override
    public int add(String name, String email, Address address) {
        log.info("SOURCE MODULE ID: {} MY MODULE ID: {}", RequestContext.getSourceModuleId(), Utils.getRequiredOption(Options.MODULE_ID));
        log.info("TICKET: {}", RequestContext.getTicket());
        Person p = new Person();
        p.setEmail(email);
        p.setName(name);
        p.setId(idProvider.addAndGet(1));
        p.setAddress(address);
        people.add(p);
        return p.getId();
    }

    @Override
    public Person get(final Integer id) {
        log.info("SOURCE MODULE ID: {} MY MODULE ID: {}", RequestContext.getSourceModuleId(), Utils.getRequiredOption(Options.MODULE_ID));
        log.info("TICKET: {}", RequestContext.getTicket());
        return this.people.stream().filter(person -> person.getId().equals(id)).findFirst().orElse(null);
    }

    @Override
    public void lol() {
        log.info("SOURCE MODULE ID: {} MY MODULE ID: {}", RequestContext.getSourceModuleId(), Utils.getRequiredOption(Options.MODULE_ID));
        log.info("TICKET: {}", RequestContext.getTicket());
        log.info("Lol");
    }

    @Override
    public void lol2(String message) {
        log.info(message);
    }

    @Override
    public String getName() {
        return null;
    }

    @Override
    public String getHeavy(String heavy) {
        return heavy;
    }

    @Override
    public Person testError() {
        throw new RuntimeException("very bad in " + Utils.getRequiredOption(Options.MODULE_ID));
    }
}