package com.example.springsoap;

import javax.annotation.PostConstruct;
import java.io.*;
import java.util.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import com.example.springsoap.exception.*;
import io.skirental.gt.webservice.*;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


@Component
public class MaterialRepository
{
    private static final String STOCK_FILE = "stock.csv";
    private static final Map<Integer, Material> stock = new HashMap<Integer, Material>();
    private static final Map<String, List<Order>> transactionChanges = new HashMap<>();
    private static final Map<Integer, Lock> stockLocks = new ConcurrentHashMap<>();
    private static final Logger logger = LoggerFactory.getLogger(MaterialRepository.class);

    @PostConstruct
    public void initData()
    {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(STOCK_FILE)) {
            if (is == null) {
                logger.error("Failed to load stock.csv - file not found in resources.");
                return;
            }
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
                br.lines().skip(1).forEach(line -> {
                    String[] parts = line.split(",");
                    int id = Integer.parseInt(parts[0]);
                    ProductType passtype = ProductType.valueOf(parts[1].toUpperCase());
                    float price = Float.parseFloat(parts[2]);
                    int availableAmount = Integer.parseInt(parts[3]);
                    Material m = new Material();
                    m.setId(id);
                    m.setProductType(passtype);
                    m.setPrice(price);
                    m.setAvailableAmount(availableAmount);
                    stock.put(id, m);
                    stockLocks.put(id, new ReentrantLock());
                });
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public Material findMaterialById(Integer id)
    {
        Assert.notNull(id, "The material's id must not be null");
        return stock.get(id);
    }

    public List<Material> getFullStock()
    {
        return new ArrayList<>(stock.values());
    }

    public Vote sendVote(String transactionId, List<Order> orders) throws IOException
    {
        logger.info("2PC - {} - Vote request received.", transactionId);

        Vote vote = new Vote();
        vote.setVote(ProtocolMessage.VOTE_COMMIT);
        vote.setError("No error, transaction prepared.");

        Map<ProductType, Integer> stockMap = new HashMap<>();

        try (InputStream is = getClass().getClassLoader().getResourceAsStream(STOCK_FILE)) {
            if (is == null) {
                logger.error("Failed to load stock.csv in sendVote - file not found in resources.");
                vote.setVote(ProtocolMessage.VOTE_ABORT);
                vote.setError("Could not read from stock");
                return vote;
            }
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
                br.readLine();
                String line;
                while ((line = br.readLine()) != null) {
                    String[] parts = line.split(",");
                    ProductType productType = ProductType.valueOf(parts[1].toUpperCase());
                    int stockCount = Integer.parseInt(parts[3]);
                    stockMap.put(productType, stockCount);
                }
            }
        }

        for (Order order : orders) {
            ProductType productType = stock.get(order.getStockId()).getProductType();
            if (productType == null) {
                vote.setVote(ProtocolMessage.VOTE_ABORT);
                vote.setError("Invalid stock ID: " + order.getStockId());
                logger.error("2PC - {} - Requested stock ID {} invalid for vote phase.", transactionId, order.getStockId());
            }

            int stockCount = stockMap.getOrDefault(productType, 0);
            if (stockCount < order.getAmount()) {
                vote.setVote(ProtocolMessage.VOTE_ABORT);
                vote.setError("Insufficient stock for pass type: " + productType + " with stock ID: " + order.getStockId());
                logger.error("2PC - {} - Insufficient stock for requested type {} for vote phase.", transactionId, productType);
            }
        }

        for (Order order : orders) {
            Lock lock = stockLocks.get(order.getStockId());
            if (lock != null) {
                lock.lock();
                logger.info("2PC - Stock locked");
            }
        }

        logger.info("2PC - {} - Vote given: {}.", transactionId, vote);
        return vote;
    }


    public ProtocolMessage bookItem(String transactionId, ProtocolMessage decision, List<Order> orders) throws IOException, InsufficientStockException, InvalidStockIdException
    {
        logger.info("2PC - {} - Received booking request with decision: {}.", transactionId, decision);

        if (decision != ProtocolMessage.GLOBAL_COMMIT) {
            for (Order order : orders) {
                ReentrantLock lock = (ReentrantLock) stockLocks.get(order.getStockId());
                if (lock != null && lock.isHeldByCurrentThread()) {
                    lock.unlock();
                    logger.info("2PC - Stock unlocked 1");
                }
            }
            return ProtocolMessage.ACKNOWLEDGE;
        }

        File tempFile = new File(STOCK_FILE + ".tmp");
        File stockFile = new File(getClass().getClassLoader().getResource(STOCK_FILE).getFile());

        Map<ProductType, Integer> requiredStockUpdates = new HashMap<>();
        for (Order order : orders) {
            ProductType productType = stock.get(order.getStockId()).getProductType();
            if (productType != null) {
                requiredStockUpdates.put(productType, requiredStockUpdates.getOrDefault(productType, 0) + order.getAmount());
            } else {
                logger.error("2PC - {} - Requested stock ID {} invalid for commit phase.", transactionId, order.getStockId());
                throw new InvalidStockIdException("Invalid stock ID: " + order.getStockId());
            }
        }

        try (InputStream is = getClass().getClassLoader().getResourceAsStream(STOCK_FILE)) {
            assert is != null;
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is));
                 BufferedWriter bw = new BufferedWriter(new FileWriter(tempFile))) {
                bw.write(br.readLine() + "\n"); // Write header

                String line;
                while ((line = br.readLine()) != null) {
                    String[] parts = line.split(",");
                    int id = Integer.parseInt(parts[0]);
                    ProductType currentProductType = ProductType.valueOf(parts[1].toUpperCase());
                    float price = Float.parseFloat(parts[2]);
                    int stockCount = Integer.parseInt(parts[3]);

                    if (requiredStockUpdates.containsKey(currentProductType)) {
                        int requiredAmount = requiredStockUpdates.get(currentProductType);
                        if (stockCount >= requiredAmount) {
                            stockCount -= requiredAmount;
                            requiredStockUpdates.remove(currentProductType);
                        } else {
                            logger.error("2PC - {} - Insufficient stock for requested type {} for commit phase.", transactionId, currentProductType);
                            throw new InsufficientStockException("Insufficient stock for pass type: " + currentProductType);
                        }
                    }
                    bw.write(id + "," + currentProductType.toString().toLowerCase() + "," + price + "," + stockCount + "\n");
                }
            }
        }
        Files.move(tempFile.toPath(), stockFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        transactionChanges.put(transactionId, orders);
        logger.info("2PC - Changes made for transactionId: {}", transactionId);

        for (Order order : orders) {
            ReentrantLock lock = (ReentrantLock) stockLocks.get(order.getStockId());
            if (lock != null && lock.isHeldByCurrentThread()) {
                lock.unlock();
                logger.info("2PC - Stock unlocked 2");
            }
        }

        return ProtocolMessage.ACKNOWLEDGE;
    }


    public void rollBack(String transactionId)
    {
        logger.info("2PC - {} - Rollback request received.", transactionId);

        List<Order> orders = transactionChanges.get(transactionId);

        if (!transactionChanges.containsKey(transactionId))
        {
            logger.warn("2PC - {} - No changes recorded for rollback.", transactionId);
        } else {

            File tempFile = new File(STOCK_FILE + ".tmp");
            File stockFile = new File(Objects.requireNonNull(getClass().getClassLoader().getResource(STOCK_FILE)).getFile());

            Map<ProductType, Integer> rollbackStockUpdates = new HashMap<>();
            for (Order order : orders) {
                ProductType passtype = stock.get(order.getStockId()).getProductType();
                if (passtype != null) {
                    rollbackStockUpdates.put(passtype, rollbackStockUpdates.getOrDefault(passtype, 0) + order.getAmount());
                }
            }

            try (InputStream is = getClass().getClassLoader().getResourceAsStream(STOCK_FILE);
                 BufferedReader br = new BufferedReader(new InputStreamReader(is));
                 BufferedWriter bw = new BufferedWriter(new FileWriter(tempFile))) {
                bw.write(br.readLine() + "\n"); // Write header

                String line;
                while ((line = br.readLine()) != null) {
                    String[] parts = line.split(",");
                    int id = Integer.parseInt(parts[0]);
                    ProductType currentProductType = ProductType.valueOf(parts[1].toUpperCase());
                    float price = Float.parseFloat(parts[2]);
                    int stockCount = Integer.parseInt(parts[3]);

                    if (rollbackStockUpdates.containsKey(currentProductType)) {
                        stockCount += rollbackStockUpdates.get(currentProductType);
                        rollbackStockUpdates.remove(currentProductType);
                    }
                    bw.write(id + "," + currentProductType.toString().toLowerCase() + "," + price + "," + stockCount + "\n");
                }
            } catch (IOException e) {
                logger.error("2PC - {} - Rollback failed.", transactionId, e);
                return;
            }
            try {
                Files.move(tempFile.toPath(), stockFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                logger.info("2PC - {} - Rollback successful.", transactionId);
            } catch (IOException e) {
                logger.error("2PC - {} - Rollback move failed.", transactionId, e);
            }

            // Remove the changes from the log
            transactionChanges.remove(transactionId);
        }

        for (Order order : orders) {
            ReentrantLock lock = (ReentrantLock) stockLocks.get(order.getStockId());
            if (lock != null && lock.isHeldByCurrentThread()) {
                lock.unlock();
                logger.info("2PC - Stock unlocked");
            }
        }
    }
}