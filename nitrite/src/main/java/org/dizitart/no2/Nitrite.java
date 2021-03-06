/*
 * Copyright 2017 Nitrite author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.dizitart.no2;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.dizitart.no2.internals.CollectionFactory;
import org.dizitart.no2.objects.ObjectRepository;
import org.dizitart.no2.objects.RepositoryFactory;
import org.dizitart.no2.store.NitriteMap;
import org.dizitart.no2.store.NitriteStore;

import java.io.Closeable;
import java.nio.channels.NonWritableChannelException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.dizitart.no2.Constants.INTERNAL_NAME_SEPARATOR;
import static org.dizitart.no2.Constants.USER_MAP;
import static org.dizitart.no2.Security.validateUserPassword;
import static org.dizitart.no2.util.ObjectUtils.findObjectTypeName;
import static org.dizitart.no2.util.ObjectUtils.findObjectStoreName;
import static org.dizitart.no2.util.ObjectUtils.isObjectStore;
import static org.dizitart.no2.util.StringUtils.isNullOrEmpty;
import static org.dizitart.no2.util.ValidationUtils.isValidCollectionName;
import static org.dizitart.no2.util.ValidationUtils.validateCollectionName;


/**
 * = Nitrite
 * 
 * An in-memory, single-file based embedded nosql persistent document store. The store
 * can contains multiple named document collections.
 * 
 * It supports following features:
 * 
 * include::/src/docs/asciidoc/features.adoc[]
 * 
 * [icon="{@docRoot}/note.png"]
 * [NOTE]
 * ====
 *  - It does not support ACID transactions.
 *  - Use {@link NitriteBuilder} to create a db instance.
 * ====
 *
 * @author Anindya Chatterjee
 * @see NitriteBuilder
 * @since 1.0
 */
@Slf4j
public class Nitrite implements Closeable {
    private NitriteStore store;

    /**
     * Provides contextual information for the nitrite database instance.
     * */
    @Getter
    private NitriteContext context;

    Nitrite(NitriteStore store, NitriteContext nitriteContext) {
        this.context = nitriteContext;
        this.store = store;
    }

    /**
     * Provides a builder utility to create a {@link Nitrite} database
     * instance.
     *
     * @return a {@link NitriteBuilder} instance.
     */
    public static NitriteBuilder builder() {
        return new NitriteBuilder();
    }

    /**
     * Opens a named collection from the store. If the collections does not
     * exist it will be created automatically and returned. If a collection
     * is already opened, it is returned as is. Returned collection is thread-safe
     * for concurrent use.
     *
     * [icon="{@docRoot}/alert.png"]
     * [CAUTION]
     * ====
     * The name can not contain below reserved strings:
     *
     * - {@link Constants#INTERNAL_NAME_SEPARATOR}
     * - {@link Constants#USER_MAP}
     * - {@link Constants#INDEX_META_PREFIX}
     * - {@link Constants#INDEX_PREFIX}
     * - {@link Constants#OBJECT_STORE_NAME_SEPARATOR}
     *
     * ====
     *
     * @param name the name of the collection
     * @return the collection
     * @see NitriteCollection
     */
    public NitriteCollection getCollection(String name) {
        validateCollectionName(name);
        if (store != null) {
            NitriteMap<NitriteId, Document> mapStore = store.openMap(name);
            return CollectionFactory.open(mapStore, context);
        } else {
            log.error("Underlying store is null. Nitrite has not been initialized properly.");
        }
        return null;
    }

    /**
     * Opens a type-safe object repository from the store. If the repository
     * does not exist it will be created automatically and returned. If a
     * repository is already opened, it is returned as is.
     * 
     * [icon="{@docRoot}/note.png"]
     * NOTE: Returned repository is thread-safe for concurrent use.
     *
     * @param <T>  the type parameter
     * @param type the type of the object
     * @return the repository containing objects of type {@link T}.
     * @see ObjectRepository
     */
    public <T> ObjectRepository<T> getRepository(Class<T> type) {
        if (store != null) {
            String name = findObjectStoreName(type);
            NitriteMap<NitriteId, Document> mapStore = store.openMap(name);
            NitriteCollection collection = CollectionFactory.open(mapStore, context);
            return RepositoryFactory.open(type, collection, context);
        } else {
            log.error("Underlying store is null. Nitrite has not been initialized properly.");
        }
        return null;
    }

    /**
     * Gets the set of all {@link NitriteCollection}s' names saved in the store.
     *
     * @return the set of all collections' names.
     */
    public Set<String> listCollectionNames() {
        Set<String> collectionNames = new LinkedHashSet<>();
        if (store != null) {
            for (String name : store.getMapNames()) {
                if (isValidCollectionName(name) && !isObjectStore(name)) {
                    collectionNames.add(name);
                }
            }
        } else {
            log.error("Underlying store is null. Nitrite has not been initialized properly.");
        }
        return collectionNames;
    }

    /**
     * Gets the set of all fully qualified class names corresponding
     * to all {@link ObjectRepository}s in the store.
     *
     * @return the set of all registered classes' names.
     */
    public Set<String> listRepositories() {
        Set<String> repositoryNames = new LinkedHashSet<>();
        if (store != null) {
            for (String name : store.getMapNames()) {
                if (!name.contains(INTERNAL_NAME_SEPARATOR)
                        && !name.contains(USER_MAP)) {
                    String objectType = findObjectTypeName(name);
                    if (!isNullOrEmpty(objectType)) {
                        repositoryNames.add(objectType);
                    }
                }
            }
        } else {
            log.error("Underlying store is null. Nitrite has not been initialized properly.");
        }
        return repositoryNames;
    }

    /**
     * Checks whether a particular {@link NitriteCollection} exists in the store.
     *
     * @param name the name of the collection.
     * @return `true` if the collection exists; otherwise `false`.
     */
    public boolean hasCollection(String name) {
        return listCollectionNames().contains(name);
    }

    /**
     * Checks whether a particular {@link ObjectRepository} exists in the store.
     *
     * @param <T>  the type parameter
     * @param type the type of the object
     * @return `true` if the repository exists; otherwise `false`.
     */
    public <T> boolean hasRepository(Class<T> type) {
        return listRepositories().contains(type.getName());
    }

    /**
     * Checks whether the store has any unsaved changes.
     *
     * @return `true` if there are unsaved changes; otherwise `false`.
     */
    public boolean hasUnsavedChanges() {
        return store != null && store.hasUnsavedChanges();
    }

    /**
     * Compacts store by moving all chunks next to each other.
     */
    public void compact() {
        if (store != null && !store.isClosed()
                && !context.isReadOnly()) {
            store.compactMoveChunks();
            if (log.isDebugEnabled()) {
                log.debug("Store compaction is successful.");
            }
        } else if (store == null) {
            log.error("Underlying store is null. Nitrite has not been initialized properly.");
        }
    }

    /**
     * Commits the changes. For file based store, it saves the changes
     * to disk if there are any unsaved changes.
     * 
     * [icon="{@docRoot}/tip.png"]
     * TIP: No need to call it after every change, if auto-commit is not disabled
     * while opening the db. However, it may still be called to flush all
     * changes to disk.
     *
     */
    public void commit() {
        if (store != null && !context.isReadOnly()) {
            store.commit();
            if (log.isDebugEnabled()) {
                log.debug("Unsaved changes committed successfully.");
            }
        } else if (store == null) {
            log.error("Underlying store is null. Nitrite has not been initialized properly.");
        }
    }

    /**
     * Closes the database. Unsaved changes are written to disk and compacted first
     * for a file based store.
     */
    public void close() {
        if (store != null) {
            try {
                if (hasUnsavedChanges()) {
                    if (log.isDebugEnabled()) {
                        log.debug("Unsaved changes detected, committing the changes.");
                    }
                    commit();
                }
                if (context.isAutoCompactEnabled()) {
                    compact();
                }

                try {
                    closeCollections();
                    context.shutdown();
                } catch (Throwable error) {
                    log.error("Error while shutting down nitrite.", error);
                }

                store.close();
            } catch (NonWritableChannelException error) {
                if (!context.isReadOnly()) {
                    throw error;
                }
            } finally {
                store = null;
                log.info("Nitrite database has been closed successfully.");
            }
        } else {
            log.error("Underlying store is null. Nitrite has not been initialized properly.");
        }
    }

    /**
     * Closes the db immediately without saving last unsaved changes.
     *
     * [icon="{@docRoot}/note.png"]
     * NOTE: This operation is called from the JVM shutdown hook to
     * avoid database corruption.
     * */
    void closeImmediately() {
        if (store != null) {
            try {
                store.closeImmediately();
                context.shutdown();
            } catch (NonWritableChannelException error) {
                if (!context.isReadOnly()) {
                    log.error("Error while closing nitrite store.", error);
                }
            } catch (Throwable t) {
                log.error("Error while closing nitrite store.", t);
            } finally {
                store = null;
                log.info("Nitrite database has been closed by JVM shutdown hook without saving last unsaved changes.");
            }
        } else {
            log.error("Underlying store is null. Nitrite has not been initialized properly.");
        }
    }

    /**
     * Checks whether the store is closed.
     *
     * @return `true` if closed; otherwise `false`.
     */
    public boolean isClosed() {
        return store == null || store.isClosed();
    }

    /**
     * Checks if a specific username and password combination is valid to access
     * the database.
     *
     * @param userId   the user id
     * @param password the password
     * @return `true` if valid; otherwise `false`.
     */
    public boolean validateUser(String userId, String password) {
        return validateUserPassword(store, userId, password);
    }

    private void closeCollections() {
        List<String> collections = context.getCollectionRegistry();
        if (collections != null) {
            for (String name : collections) {
                NitriteCollection collection = getCollection(name);
                if (collection != null && !collection.isClosed()) {
                    collection.close();
                }
            }
            collections.clear();
        }

        List<Class<?>> repositories = context.getRepositoryRegistry();
        if (repositories != null) {
            for (Class<?> type : repositories) {
                ObjectRepository<?> repository = getRepository(type);
                if (repository != null && !repository.isClosed()) {
                    repository.close();
                }
            }
            repositories.clear();
        }
    }
}
