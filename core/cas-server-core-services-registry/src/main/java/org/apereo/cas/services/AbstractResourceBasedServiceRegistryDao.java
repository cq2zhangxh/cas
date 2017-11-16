package org.apereo.cas.services;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apereo.cas.DistributedCacheManager;
import org.apereo.cas.DistributedCacheObject;
import org.apereo.cas.support.events.service.CasRegisteredServiceLoadedEvent;
import org.apereo.cas.support.events.service.CasRegisteredServicePreSaveEvent;
import org.apereo.cas.support.events.service.CasRegisteredServiceSavedEvent;
import org.apereo.cas.support.events.service.CasRegisteredServicesLoadedEvent;
import org.apereo.cas.util.CollectionUtils;
import org.apereo.cas.util.RegexUtils;
import org.apereo.cas.util.ResourceUtils;
import org.apereo.cas.util.io.LockedOutputStream;
import org.apereo.cas.util.io.PathWatcherService;
import org.apereo.cas.util.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.Resource;
import org.springframework.util.Assert;

import javax.annotation.PreDestroy;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BinaryOperator;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * This is {@link AbstractResourceBasedServiceRegistryDao}.
 *
 * @author Misagh Moayyed
 * @since 5.0.0
 */
public abstract class AbstractResourceBasedServiceRegistryDao extends AbstractServiceRegistryDao implements ResourceBasedServiceRegistryDao {
    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractResourceBasedServiceRegistryDao.class);

    private static final Consumer<RegisteredService> LOG_SERVICE_DUPLICATE =
        service -> LOGGER.warn("Found a service definition [{}] with a duplicate id [{}]. "
                    + "This will overwrite previous service definitions and is likely a configuration problem. "
                    + "Make sure all services have a unique id and try again.", service.getServiceId(), service.getId());

    private static final BinaryOperator<RegisteredService> LOG_DUPLICATE_AND_RETURN_FIRST_ONE = (s1, s2) -> {
        LOG_SERVICE_DUPLICATE.accept(s2);
        return s1;
    };

    /**
     * The Service registry directory.
     */
    protected Path serviceRegistryDirectory;

    /**
     * Map of service ID to registered service.
     */
    private Map<Long, RegisteredService> serviceMap = new ConcurrentHashMap<>();

    /**
     * The Registered service json serializers.
     */
    private Collection<StringSerializer<RegisteredService>> registeredServiceSerializers;

    private PathWatcherService serviceRegistryConfigWatcher;

    private Pattern serviceFileNamePattern;

    private DistributedCacheManager<RegisteredService, DistributedCacheObject<RegisteredService>> distributedCacheManager =
            new NoOpDistributedCacheManager();

    /**
     * Instantiates a new service registry dao.
     *
     * @param configDirectory the config directory
     * @param serializer      the registered service json serializer
     * @param enableWatcher   enable watcher thread
     * @param eventPublisher  the event publisher
     */
    public AbstractResourceBasedServiceRegistryDao(final Path configDirectory,
                                                   final StringSerializer<RegisteredService> serializer,
                                                   final boolean enableWatcher,
                                                   final ApplicationEventPublisher eventPublisher,
                                                   final DistributedCacheManager distributedCacheManager) {
        this(configDirectory, CollectionUtils.wrap(serializer), enableWatcher, eventPublisher, distributedCacheManager);
    }

    /**
     * Instantiates a new Abstract resource based service registry dao.
     *
     * @param configDirectory the config directory
     * @param serializers     the serializers
     * @param enableWatcher   the enable watcher
     * @param eventPublisher  the event publisher
     */
    public AbstractResourceBasedServiceRegistryDao(final Path configDirectory,
                                                   final Collection<StringSerializer<RegisteredService>> serializers,
                                                   final boolean enableWatcher,
                                                   final ApplicationEventPublisher eventPublisher,
                                                   final DistributedCacheManager distributedCacheManager) {
        initializeRegistry(configDirectory, serializers, enableWatcher, eventPublisher, distributedCacheManager);
    }

    /**
     * Instantiates a new Abstract resource based service registry dao.
     *
     * @param configDirectory the config directory
     * @param serializers     the serializers
     * @param enableWatcher   the enable watcher
     * @param eventPublisher  the event publisher
     * @throws Exception the exception
     */
    public AbstractResourceBasedServiceRegistryDao(final Resource configDirectory,
                                                   final Collection<StringSerializer<RegisteredService>> serializers,
                                                   final boolean enableWatcher,
                                                   final ApplicationEventPublisher eventPublisher,
                                                   final DistributedCacheManager distributedCacheManager) throws Exception {

        final Resource servicesDirectory = ResourceUtils.prepareClasspathResourceIfNeeded(configDirectory, true, getExtension());
        if (servicesDirectory == null) {
            throw new IllegalArgumentException("Could not determine the services configuration directory from " + configDirectory);
        }
        final File file = servicesDirectory.getFile();
        initializeRegistry(Paths.get(file.getCanonicalPath()), serializers, enableWatcher, eventPublisher, distributedCacheManager);
    }

    private void initializeRegistry(final Path configDirectory,
                                    final Collection<StringSerializer<RegisteredService>> serializers,
                                    final boolean enableWatcher,
                                    final ApplicationEventPublisher eventPublisher,
                                    final DistributedCacheManager distributedCacheManager) {
        setEventPublisher(eventPublisher);

        this.distributedCacheManager = distributedCacheManager;
        this.serviceFileNamePattern = RegexUtils.createPattern("\\w+-\\d+\\." + getExtension());

        this.serviceRegistryDirectory = configDirectory;
        Assert.isTrue(this.serviceRegistryDirectory.toFile().exists(), this.serviceRegistryDirectory + " does not exist");
        Assert.isTrue(this.serviceRegistryDirectory.toFile().isDirectory(), this.serviceRegistryDirectory + " is not a directory");
        this.registeredServiceSerializers = serializers;

        if (enableWatcher) {
            enableServicesDirectoryPathWatcher(configDirectory);
        }
    }

    private void enableServicesDirectoryPathWatcher(final Path configDirectory) {
        LOGGER.info("Setting up a watch for service registry directory at [{}]", configDirectory);

        final Consumer<File> onCreate = file -> {
            LOGGER.debug("New service definition [{}] was created. Locating service entry from cache...", file);
            final Collection<RegisteredService> services = load(file);
            services.stream()
                    .filter(Objects::nonNull)
                    .forEach(service -> {
                        if (findServiceById(service.getId()) != null) {
                            LOG_SERVICE_DUPLICATE.accept(service);
                        }
                        LOGGER.debug("Updating service definitions with [{}]", service);
                        publishEvent(new CasRegisteredServicePreSaveEvent(this, service));
                        update(service);
                        publishEvent(new CasRegisteredServiceSavedEvent(this, service));
                    });
        };
        final Consumer<File> onDelete = file -> {
            LOGGER.debug("Service definition [{}] was deleted. Reloading cache...", file);
            final List<RegisteredService> results = load();
            publishEvent(new CasRegisteredServicesLoadedEvent(this, results));
        };
        final Consumer<File> onModify = file -> {
            LOGGER.debug("New service definition [{}] was modified. Locating service entry from cache...", file);
            final Collection<RegisteredService> newServices = load(file);
            newServices.stream()
                    .filter(Objects::nonNull)
                    .forEach(newService -> {
                        final RegisteredService oldService = findServiceById(newService.getId());

                        if (!newService.equals(oldService)) {
                            LOGGER.debug("Updating service definitions with [{}]", newService);
                            publishEvent(new CasRegisteredServicePreSaveEvent(this, newService));
                            update(newService);
                            publishEvent(new CasRegisteredServiceSavedEvent(this, newService));
                        } else {
                            LOGGER.debug("Service [{}] loaded from [{}] is identical to the existing entry. Entry may have already been saved "
                                    + "in the event processing pipeline", newService.getId(), file.getName());
                        }
                    });
        };
        this.serviceRegistryConfigWatcher = new PathWatcherService(serviceRegistryDirectory, onCreate, onModify, onDelete);
        this.serviceRegistryConfigWatcher.start(getClass().getSimpleName());
        LOGGER.debug("Started service registry watcher thread");
    }

    /**
     * Destroy the watch service thread.
     *
     * @throws Exception the exception
     */
    @PreDestroy
    public void destroy() throws Exception {
        if (this.distributedCacheManager != null) {
            this.distributedCacheManager.close();
        }
        if (this.serviceRegistryConfigWatcher != null) {
            this.serviceRegistryConfigWatcher.close();
        }
    }

    @Override
    public long size() {
        return this.serviceMap.size();
    }

    @Override
    public RegisteredService findServiceById(final long id) {
        final RegisteredService service = this.serviceMap.get(id);
        return getRegisteredServiceFromCacheIfAny(service, id);
    }

    @Override
    public RegisteredService findServiceById(final String id) {
        final RegisteredService service = this.serviceMap.values()
                .stream()
                .filter(r -> r.matches(id))
                .findFirst()
                .orElse(null);
        return getRegisteredServiceFromCacheIfAny(service, id);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName();
    }

    @Override
    public synchronized boolean delete(final RegisteredService service) {
        try {
            final File f = getRegisteredServiceFileName(service);
            final boolean result = f.delete();
            if (!result) {
                LOGGER.warn("Failed to delete service definition file [{}]", f.getCanonicalPath());
            } else {
                this.serviceMap.remove(service.getId());
                LOGGER.debug("Successfully deleted service definition file [{}]", f.getCanonicalPath());
            }
            return result;
        } catch (final IOException e) {
            throw new IllegalArgumentException(e);
        }
    }


    @Override
    public synchronized List<RegisteredService> load() {
        final Collection<File> files = FileUtils.listFiles(this.serviceRegistryDirectory.toFile(), new String[]{getExtension()}, true);
        this.serviceMap = files.stream()
                .map(this::load)
                .filter(Objects::nonNull)
                .flatMap(Collection::stream)
                .sorted()
                .collect(Collectors.toMap(RegisteredService::getId, Function.identity(),
                        LOG_DUPLICATE_AND_RETURN_FIRST_ONE, LinkedHashMap::new));
        final List<RegisteredService> results = updateLoadedRegisteredServicesFromCache(new ArrayList<>(this.serviceMap.values()));
        results.forEach(service -> publishEvent(new CasRegisteredServiceLoadedEvent(this, service)));
        return results;
    }

    /**
     * Load registered service from file.
     *
     * @param file the file
     * @return the registered service, or null if file cannot be read, is not found, is empty or parsing error occurs.
     */
    @Override
    public Collection<RegisteredService> load(final File file) {
        if (!file.canRead()) {
            LOGGER.warn("[{}] is not readable. Check file permissions", file.getName());
            return new ArrayList<>(0);
        }

        if (!file.exists()) {
            LOGGER.warn("[{}] is not found at the path specified", file.getName());
            return new ArrayList<>(0);
        }

        if (file.length() == 0) {
            LOGGER.debug("[{}] appears to be empty so no service definition will be loaded", file.getName());
            return new ArrayList<>(0);
        }

        if (!RegexUtils.matches(this.serviceFileNamePattern, file.getName())) {
            LOGGER.warn("[{}] does not match the recommended pattern [{}]. "
                            + "While CAS tries to be forgiving as much as possible, it's recommended "
                            + "that you rename the file to match the requested pattern to avoid issues with duplicate service loading. "
                            + "Future CAS versions may try to strictly force the naming syntax, refusing to load the file.",
                    file.getName(), this.serviceFileNamePattern.pattern());
        }

        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(file))) {
            return this.registeredServiceSerializers
                    .stream()
                    .filter(s -> s.supports(file))
                    .map(s -> s.load(in))
                    .filter(Objects::nonNull)
                    .flatMap(Collection::stream)
                    .collect(Collectors.toList());
        } catch (final Exception e) {
            LOGGER.error("Error reading configuration file [{}]", file.getName(), e);
        }
        return new ArrayList<>(0);
    }

    @Override
    public RegisteredService save(final RegisteredService service) {
        if (service.getId() == RegisteredService.INITIAL_IDENTIFIER_VALUE && service instanceof AbstractRegisteredService) {
            LOGGER.debug("Service id not set. Calculating id based on system time...");
            ((AbstractRegisteredService) service).setId(System.currentTimeMillis());
        }
        final File f = getRegisteredServiceFileName(service);
        try (LockedOutputStream out = new LockedOutputStream(new FileOutputStream(f))) {
            final boolean result = this.registeredServiceSerializers
                    .stream()
                    .anyMatch(s -> {
                        try {
                            s.to(out, service);
                            return true;
                        } catch (final Exception e) {
                            LOGGER.debug(e.getMessage(), e);
                            return false;
                        }
                    });

            if (!result) {
                throw new IOException("The service definition file could not be saved at " + f.getCanonicalPath());
            }
            if (this.serviceMap.containsKey(service.getId())) {
                LOGGER.debug("Found existing service definition by id [{}]. Saving...", service.getId());
            }
            this.serviceMap.put(service.getId(), service);
            LOGGER.debug("Saved service to [{}]", f.getCanonicalPath());
        } catch (final IOException e) {
            throw new IllegalArgumentException("IO error opening file stream.", e);
        }
        return findServiceById(service.getId());
    }


    @Override
    public void update(final RegisteredService service) {
        this.serviceMap.put(service.getId(), service);
    }

    @Override
    public DistributedCacheManager getCacheManager() {
        return this.distributedCacheManager;
    }

    /**
     * Creates a file for a registered service.
     * The file is named as {@code [SERVICE-NAME]-[SERVICE-ID]-.{@value #getExtension()}}
     *
     * @param service Registered service.
     * @return file in service registry directory.
     * @throws IllegalArgumentException if file name is invalid
     */
    protected File getRegisteredServiceFileName(final RegisteredService service) {
        final String fileName = StringUtils.remove(buildServiceDefinitionFileName(service), " ");
        try {
            final File svcFile = new File(this.serviceRegistryDirectory.toFile(), fileName);
            LOGGER.debug("Using [{}] as the service definition file", svcFile.getCanonicalPath());
            return svcFile;
        } catch (final Exception e) {
            LOGGER.warn("Service file name [{}] is invalid; Examine for illegal characters in the name.", fileName);
            throw new IllegalArgumentException(e);
        }
    }

    private String buildServiceDefinitionFileName(final RegisteredService service) {
        return service.getName() + '-' + service.getId() + '.' + getExtension();
    }

    /**
     * Gets extension associated with files in the given resource directory.
     *
     * @return the extension
     */
    protected abstract String getExtension();

    private RegisteredService getRegisteredServiceFromCacheIfAny(final RegisteredService service, final String id) {
        return getRegisteredServiceFromCacheByPredicate(service, value -> value.getValue().matches(id));
    }

    private RegisteredService getRegisteredServiceFromCacheIfAny(final RegisteredService service, final long id) {
        return getRegisteredServiceFromCacheByPredicate(service, value -> value.getValue().getId() == id);
    }

    private RegisteredService getRegisteredServiceFromCacheByPredicate(final RegisteredService service,
                                                                       final Predicate<DistributedCacheObject<RegisteredService>> predicate) {
        final Optional<DistributedCacheObject<RegisteredService>> result = this.distributedCacheManager.find(predicate);
        if (result.isPresent()) {
            final DistributedCacheObject<RegisteredService> item = result.get();
            LOGGER.debug("Located cache entry [{}] in service registry cache [{}]", item, this.distributedCacheManager.getName());
            if (service == null) {
                LOGGER.info("Service is in not found in the local service registry for this CAS node. CAS will use the cache entry [{}] instead "
                        + "and will update the service registry of this CAS node with the cache entry for future look-ups", item.getValue());
                save(item.getValue());
                return item.getValue();
            }
            LOGGER.debug("Service definition cache entry [{}] carries the timestamp [{}]", item.getValue(), item.getTimestamp());
            if (item.getValue().equals(service)) {
                LOGGER.debug("Service definition cache entry is the same as service definition found locally");
                return service;
            }
            LOGGER.info("Service definition found in the cache [{}] is more recent than its counterpart on this CAS node. CAS will "
                    + "use the cache entry and update the service registry of this CAS node with the cache entry for future look-ups", item.getValue());
            save(item.getValue());
            return item.getValue();
        }
        LOGGER.debug("Requested service definition is not found in the replication cache");
        if (service != null) {
            LOGGER.debug("Attempting to update replication cache with service [{}}", service);
            final DistributedCacheObject<RegisteredService> item = new DistributedCacheObject<>(service);
            this.distributedCacheManager.set(service, item);
        }
        return service;
    }

    /**
     * Update loaded registered services from cache.
     *
     * @param services the services loaded locally by this node.
     */
    private List<RegisteredService> updateLoadedRegisteredServicesFromCache(final List<RegisteredService> services) {
        final Collection<DistributedCacheObject<RegisteredService>> cachedServices = this.distributedCacheManager.getAll();

        for (final DistributedCacheObject<RegisteredService> entry : cachedServices) {
            final RegisteredService cachedService = entry.getValue();
            LOGGER.debug("Found cached service definition [{}] in the replication cache [{}]", cachedService, distributedCacheManager.getName());
            final RegisteredService matchingService = services.stream().filter(s -> s.getId() == cachedService.getId()).findFirst().orElse(null);
            if (matchingService != null) {
                LOGGER.debug("Found corresponding service definition [{}] locally", matchingService, distributedCacheManager.getName());
                if (matchingService.equals(cachedService)) {
                    LOGGER.debug("Service definition cache entry [{}] is the same as service definition found locally [{}]", cachedService, matchingService);
                } else {
                    LOGGER.info("Service definition found in the cache [{}] is more recent than its counterpart on this CAS node. "
                            + "CAS will update the service registry of this CAS node with the cache entry for future look-ups", cachedService);
                    save(cachedService);
                    services.add(cachedService);
                }
            } else {
                LOGGER.info("No corresponding service definition could be matched against cache entry [{}] locally"
                        + "CAS will update the service registry of this CAS node with the cache entry for future look-ups", cachedService);
                save(cachedService);
                services.add(cachedService);
            }
        }
        return services;
    }
}
