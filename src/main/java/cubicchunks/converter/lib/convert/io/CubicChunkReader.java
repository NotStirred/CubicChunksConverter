/*
 *  This file is part of CubicChunksConverter, licensed under the MIT License (MIT).
 *
 *  Copyright (c) 2017 contributors
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in
 *  all copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *  THE SOFTWARE.
 */
package cubicchunks.converter.lib.convert.io;

import com.carrotsearch.hppc.IntArrayList;
import com.carrotsearch.hppc.cursors.IntCursor;
import cubicchunks.converter.lib.Dimension;
import cubicchunks.converter.lib.convert.data.CubicChunksColumnData;
import cubicchunks.converter.lib.util.RWLockingCachedRegionProvider;
import cubicchunks.converter.lib.util.UncheckedInterruptedException;
import cubicchunks.converter.lib.util.Utils;
import cubicchunks.regionlib.impl.EntryLocation2D;
import cubicchunks.regionlib.impl.EntryLocation3D;
import cubicchunks.regionlib.impl.SaveCubeColumns;
import cubicchunks.regionlib.impl.save.SaveSection2D;
import cubicchunks.regionlib.impl.save.SaveSection3D;
import cubicchunks.regionlib.lib.ExtRegion;
import cubicchunks.regionlib.lib.provider.SimpleRegionProvider;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import static cubicchunks.converter.lib.util.Utils.interruptibleConsumer;

public class CubicChunkReader extends BaseMinecraftReader<CubicChunksColumnData, SaveCubeColumns> {

    private final CompletableFuture<ChunkList> chunkList = new CompletableFuture<>();
    private final Thread loadThread;

    public CubicChunkReader(Path srcDir) {
        super(srcDir, (dim, path) -> Files.exists(getDimensionPath(dim, path)) ? createSave(getDimensionPath(dim, path)) : null);
        loadThread = Thread.currentThread();
    }

    private static Path getDimensionPath(Dimension d, Path worldDir) {
        if (!d.getDirectory().isEmpty()) {
            worldDir = worldDir.resolve(d.getDirectory());
        }
        return worldDir;
    }

    @Override public void countInputChunks(Runnable increment) throws IOException {
        try {
            Map<Dimension, Map<EntryLocation2D, IntArrayList>> dimensions = doCountChunks(increment);
            chunkList.complete(new ChunkList(dimensions));
        } catch (UncheckedInterruptedException ex) {
            chunkList.complete(null);
        }
    }

    private Map<Dimension, Map<EntryLocation2D, IntArrayList>> doCountChunks(Runnable increment) throws IOException, UncheckedInterruptedException {
        Map<Dimension, Map<EntryLocation2D, IntArrayList>> dimensions = new HashMap<>();
        for (Map.Entry<Dimension, SaveCubeColumns> entry : saves.entrySet()) {
            SaveCubeColumns save = entry.getValue();
            Dimension dim = entry.getKey();
            Map<EntryLocation2D, IntArrayList> chunks = dimensions.computeIfAbsent(dim, p -> new HashMap<>());
            save.getSaveSection3D().forAllKeys(interruptibleConsumer(loc -> {

                EntryLocation2D loc2d = new EntryLocation2D(loc.getEntryX(), loc.getEntryZ());
                chunks.computeIfAbsent(loc2d, l -> {
                    increment.run();
                    return new IntArrayList();
                }).add(loc.getEntryY());
            }));
        }
        return dimensions;
    }

    @Override public void loadChunks(Consumer<? super CubicChunksColumnData> consumer) throws IOException, InterruptedException {
        try {
            ChunkList list = chunkList.get();
            if (list == null) {
                return; // counting interrupted
            }
            doLoadChunks(consumer, list);
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    private void doLoadChunks(Consumer<? super CubicChunksColumnData> consumer, ChunkList list) throws IOException {
        for (Map.Entry<Dimension, Map<EntryLocation2D, IntArrayList>> dimEntry : list.getChunks().entrySet()) {
            if (Thread.interrupted()) {
                return;
            }
            Dimension dim = dimEntry.getKey();
            SaveCubeColumns save = saves.get(dim);
            dimEntry.getValue().entrySet().parallelStream().forEach(chunksEntry -> {
                if (Thread.interrupted()) {
                    return;
                }
                try {
                    EntryLocation2D pos2d = chunksEntry.getKey();
                    IntArrayList yCoords = chunksEntry.getValue();
                    ByteBuffer column = save.load(pos2d, true).orElse(null);
                    Map<Integer, ByteBuffer> cubes = new HashMap<>();
                    for (IntCursor yCursor : yCoords) {
                        if (Thread.interrupted()) {
                            return;
                        }
                        int y = yCursor.value;
                        ByteBuffer cube = save.load(new EntryLocation3D(pos2d.getEntryX(), y, pos2d.getEntryZ()), true).orElseThrow(
                                () -> new IllegalStateException("Expected cube at " + pos2d + " at y=" + y + " in dimension " + dim));
                        cubes.put(y, cube);
                    }
                    CubicChunksColumnData data = new CubicChunksColumnData(dim, pos2d, column, cubes);
                    consumer.accept(data);
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            });
        }
    }

    @Override public void stop() {
        loadThread.interrupt();
    }

    private static SaveCubeColumns createSave(Path path) {
        try {
            Utils.createDirectories(path);

            Path part2d = path.resolve("region2d");
            Utils.createDirectories(part2d);

            Path part3d = path.resolve("region3d");
            Utils.createDirectories(part3d);

            SaveSection2D section2d = new SaveSection2D(
                    new RWLockingCachedRegionProvider<>(
                            SimpleRegionProvider.createDefault(new EntryLocation2D.Provider(), part2d, 512)
                    ),
                    new RWLockingCachedRegionProvider<>(
                            new SimpleRegionProvider<>(new EntryLocation2D.Provider(), part2d,
                                    (keyProvider, regionKey) -> new ExtRegion<>(part2d, Collections.emptyList(), keyProvider, regionKey),
                                    (dir, key) -> Files.exists(dir.resolve(key.getRegionKey().getName() + ".ext"))
                            )
                    ));
            SaveSection3D section3d = new SaveSection3D(
                    new RWLockingCachedRegionProvider<>(
                            SimpleRegionProvider.createDefault(new EntryLocation3D.Provider(), part3d, 512)
                    ),
                    new RWLockingCachedRegionProvider<>(
                            new SimpleRegionProvider<>(new EntryLocation3D.Provider(), part3d,
                                    (keyProvider, regionKey) -> new ExtRegion<>(part3d, Collections.emptyList(), keyProvider, regionKey),
                                    (dir, key) -> Files.exists(dir.resolve(key.getRegionKey().getName() + ".ext"))
                            )
                    ));

            return new SaveCubeColumns(section2d, section3d);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static class ChunkList {

        private final Map<Dimension, Map<EntryLocation2D, IntArrayList>> chunks;

        private ChunkList(Map<Dimension, Map<EntryLocation2D, IntArrayList>> chunks) {
            this.chunks = chunks;
        }

        Map<Dimension, Map<EntryLocation2D, IntArrayList>> getChunks() {
            return chunks;
        }
    }
}
