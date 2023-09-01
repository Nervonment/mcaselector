package net.querz.mcaselector.io.job;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.querz.mcaselector.io.*;
import net.querz.mcaselector.io.mca.*;
import net.querz.mcaselector.overlay.Overlay;
import net.querz.mcaselector.point.Point2i;
import net.querz.mcaselector.progress.Progress;
import net.querz.mcaselector.selection.ChunkSet;
import net.querz.mcaselector.selection.SelectionData;
import net.querz.mcaselector.tile.Tile;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class SelectionSummer {

    private static final Logger LOGGER = LogManager.getLogger(SelectionSummer.class);

    private SelectionSummer() {}

    public static AtomicInteger sumSelection(int selected, SelectionData data, Overlay overlay, Progress progressChannel) {
        AtomicInteger answer = new AtomicInteger();
        answer.set(0);

        JobHandler.clearQueues();

        progressChannel.setMax(selected);
        progressChannel.updateProgress("0", 0);

        LOGGER.debug("creating counting jobs: {}", data);

        Consumer<Throwable> errorHandler = t -> {
            answer.set(-1);
            progressChannel.done("error");
            //JobHandler.clearQueues();
        };

        for (Long2ObjectMap.Entry<ChunkSet> entry : data.getSelection()) {
            SelectionSummer.SumInSelectionProcessJob job = new SelectionSummer.SumInSelectionProcessJob(new Point2i(entry.getLongKey()), entry.getValue(), data, answer, overlay, progressChannel);
            job.errorHandler = errorHandler;
            JobHandler.addJob(job);
        }

        return answer;
    }

    private static class SumInSelectionProcessJob extends ProcessDataJob {

        private final AtomicInteger sum;
        private final ChunkSet chunks;
        private final SelectionData seldata;
        private final Progress progressChannel;
        private final Overlay parser;

        public SumInSelectionProcessJob(Point2i region, ChunkSet chunks, SelectionData seldata, AtomicInteger sum, Overlay parser, Progress progressChannel) {
            super(new RegionDirectories(region, null, null, null), PRIORITY_LOW);
            this.sum = sum;
            this.chunks = chunks;
            this.seldata = seldata;
            this.progressChannel = progressChannel;
            this.parser = parser;
        }

        @Override
        public boolean execute() {
            File regionFile = FileHelper.createRegionMCAFilePath(getRegionDirectories().getLocation());
            File poiFile = FileHelper.createPoiMCAFilePath(getRegionDirectories().getLocation());
            File entitiesFile = FileHelper.createEntitiesMCAFilePath(getRegionDirectories().getLocation());

            if (!regionFile.exists() && !poiFile.exists() && !entitiesFile.exists()) {
                progressChannel.incrementProgress(String.valueOf(sum.get()), getSize(chunks));
                return true;
            }

            RegionMCAFile regionMCAFile = null;
            if (regionFile.exists()) {
                byte[] regionData = load(regionFile);
                if (regionData != null) {
                    regionMCAFile = new RegionMCAFile(regionFile);
                    try {
                        regionMCAFile.load(new ByteArrayPointer(regionData));
                    } catch (IOException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }

            PoiMCAFile poiMCAFile = null;
            if (poiFile.exists()) {
                byte[] poiData = load(poiFile);
                if (poiData != null) {
                    poiMCAFile = new PoiMCAFile(poiFile);
                    try {
                        poiMCAFile.load(new ByteArrayPointer(poiData));
                    } catch (IOException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }

            EntitiesMCAFile entitiesMCAFile = null;
            if (entitiesFile.exists()) {
                byte[] entitiesData = load(entitiesFile);
                if (entitiesData != null) {
                    entitiesMCAFile = new EntitiesMCAFile(entitiesFile);
                    try {
                        entitiesMCAFile.load(new ByteArrayPointer(entitiesData));
                    } catch (IOException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }


            var finalRegionMCAFile = regionMCAFile;
            var finalPoiMCAFile = poiMCAFile;
            var finalEntitiesMCAFile = entitiesMCAFile;
            iterateChunks(chunks, getRegionDirectories().getLocation(), chunk -> {
                if (progressChannel.taskCancelled()) return;

                var regionChunk = finalRegionMCAFile != null ? finalRegionMCAFile.getChunkAt(chunk) : null;
                var poiChunk = finalPoiMCAFile != null ? finalPoiMCAFile.getChunkAt(chunk) : null;
                var entitiesChunk = finalEntitiesMCAFile != null ? finalEntitiesMCAFile.getChunkAt(chunk) : null;

                int chunkValue;
                try{
                    chunkValue = new ChunkData(regionChunk, poiChunk, entitiesChunk).parseData(parser);
                } catch (Exception e){
                    LOGGER.warn(e);
                    chunkValue = 0;
                }

                int current = sum.addAndGet(chunkValue);
                if (current < 0) throw new ArithmeticException("counter overflow");

                progressChannel.incrementProgress(String.valueOf(current));
            });

            return true;
        }

    }

    private static int getSize(ChunkSet chunks){
        return chunks != null ? chunks.size() : Tile.SIZE_IN_CHUNKS * Tile.SIZE_IN_CHUNKS;
    }

    private static void iterateChunks(ChunkSet chunks, Point2i region, Consumer<Point2i> chunkConsumer) {
        if (chunks == null) {
            Point2i regionChunk = region.regionToChunk();
            for (int x = regionChunk.getX(); x < regionChunk.getX() + 32; x++) {
                for (int z = regionChunk.getZ(); z < regionChunk.getZ() + 32; z++) {
                    chunkConsumer.accept(new Point2i(x, z));
                }
            }
        } else {
            Point2i regionChunk = region.regionToChunk();
            chunks.forEach(chunk -> chunkConsumer.accept(regionChunk.add(new Point2i(chunk))));
        }
    }

}
