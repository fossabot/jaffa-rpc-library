package com.transport.lib.spring;

import com.transport.lib.annotations.ApiClient;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.implementation.StubMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

import java.util.HashSet;
import java.util.Set;

import static net.bytebuddy.matcher.ElementMatchers.any;

/*
    Class that define and inject empty classes-implementations of provided @ApiClient interfaces.
    This allows us to use @ApiClient interfaces as Spring Beans and inject them
 */
@Configuration
@DependsOn({"serverEndpoints", "clientEndpoints"})
public class BeanStubLoader implements BeanDefinitionRegistryPostProcessor {

    private static Logger logger = LoggerFactory.getLogger(BeanStubLoader.class);

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {

        // List of client interfaces-endpoints provided by user
        ClientEndpoints clientEndpoints = ((DefaultListableBeanFactory) registry).getBean(ClientEndpoints.class);
        // Current classLoader into which new stub classes will be injected
        ClassLoader cl = BeanStubLoader.class.getClassLoader();

        // Set to exclude duplicate clients
        Set<Class<?>> annotated = new HashSet<>();

        // For each client endpoint
        for (Class<?> client : clientEndpoints.getEndpoints()) {
            // We check if @ApiClient annotation presents
            // It means interface was generated by transport-maven-plugin
            boolean isClient = client.isAnnotationPresent(ApiClient.class);
            logger.info("Client endpoint: {} isClient: {}", client.getName(), isClient);
            // Otherwise it's illegal to use interface as client endpoint
            if (!isClient)
                throw new IllegalArgumentException("Class " + client.getName() + " is not annotated as ApiClient!");
            annotated.add(client);
        }

        // Generate empty stub implementations for client interfaces
        // Inject them into the current classloader
        for (Class<?> client : annotated) {
            if (client.isInterface()) {
                Class<?> stubClass = new ByteBuddy()
                        .subclass(client)
                        .method(any())
                        .intercept(StubMethod.INSTANCE)
                        .make().load(cl, ClassLoadingStrategy.Default.INJECTION)
                        .getLoaded();
                // Then register those classes as bean definitions for later processing by Spring
                BeanDefinitionBuilder builder = BeanDefinitionBuilder.genericBeanDefinition(stubClass);
                registry.registerBeanDefinition(client.getSimpleName() + "Stub", builder.getBeanDefinition());
            }
        }
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory configurableListableBeanFactory) {
        // No-op
    }
}