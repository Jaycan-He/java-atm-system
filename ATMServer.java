package com.netATM;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class ATMServer {
    private static final int DEFAULT_PORT = 2525;
    private static final Map<String, String> userPasswords = new ConcurrentHashMap<>();
    private static final Map<String, Double> userBalances = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        int port = DEFAULT_PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
                if (port < 1024 || port > 65535) {
                    System.err.println("端口号范围应为 1024~65535，将使用默认端口 " + DEFAULT_PORT);
                    port = DEFAULT_PORT;
                }
            } catch (NumberFormatException e) {
                System.err.println("无效的端口号，将使用默认端口 " + DEFAULT_PORT);
            }
        }

        loadUserData();
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("ATM服务器已启动，监听端口 " + port);
            ExecutorService pool = Executors.newCachedThreadPool();
            while (true) {
                Socket clientSocket = serverSocket.accept();
                pool.execute(new ClientHandler(clientSocket));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void loadUserData() {
        try (BufferedReader br = new BufferedReader(new FileReader("idea/src/com.itheima.demo/netATM/users"))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length == 2) {
                    userPasswords.put(parts[0], parts[1]);
                }
            }
        } catch (IOException e) {
            System.err.println("读取users失败，将使用空数据集: " + e.getMessage());
        }

        try (BufferedReader br = new BufferedReader(new FileReader("idea/src/com.itheima.demo/netATM/balances"))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length == 2) {
                    try {
                        userBalances.put(parts[0], Double.parseDouble(parts[1]));
                    } catch (NumberFormatException ignored) {}
                }
            }
        } catch (IOException e) {
            System.err.println("读取balances失败，将使用空数据集: " + e.getMessage());
        }
    }

    private static synchronized void saveBalances() {
        try (PrintWriter pw = new PrintWriter(new FileWriter("idea/src/com.itheima.demo/netATM/balances"))) {
            for (Map.Entry<String, Double> entry : userBalances.entrySet()) {
                pw.printf("%s %.2f%n", entry.getKey(), entry.getValue());
            }
        } catch (IOException e) {
            System.err.println("保存balances失败: " + e.getMessage());
        }
    }

    private static class ClientHandler implements Runnable {
        private final Socket socket;
        private String currentCard = null;
        private boolean authenticated = false;

        ClientHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                 PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
                String line;
                while ((line = in.readLine()) != null) {
                    System.out.println("收到: " + line);
                    String response = processCommand(line.trim());
                    out.println(response);
                    System.out.println("回复: " + response);
                    if (response.equals("BYE")) break;
                }
            } catch (IOException e) {
                System.err.println("客户端连接异常: " + e.getMessage());
            } finally {
                try { socket.close(); } catch (IOException ignored) {}
                System.out.println("客户端断开，卡号: " + (currentCard == null ? "未知" : currentCard));
            }
        }

        private String processCommand(String cmd) {
            String[] parts = cmd.split("\\s+", 2);
            String command = parts[0].toUpperCase();

            switch (command) {
                case "HELO":
                    if (parts.length < 2) return "401 ERROR!";
                    currentCard = parts[1];
                    if (userPasswords.containsKey(currentCard)) {
                        authenticated = false;
                        return "500 AUTH REQUIRE";
                    } else {
                        currentCard = null;
                        return "401 ERROR!";
                    }
                case "PASS":
                    if (currentCard == null || parts.length < 2) return "401 ERROR!";
                    String pwd = parts[1];
                    if (userPasswords.getOrDefault(currentCard, "").equals(pwd)) {
                        authenticated = true;
                        return "525 OK!";
                    } else {
                        return "401 ERROR!";
                    }
                case "BALA":
                    if (!authenticated) return "401 ERROR!";
                    Double bal = userBalances.get(currentCard);
                    return bal == null ? "401 ERROR!" : String.format("AMNT:%.2f", bal);
                case "WDRA":
                    if (!authenticated || parts.length < 2) return "401 ERROR!";
                    double amount;
                    try {
                        amount = Double.parseDouble(parts[1]);
                        if (amount <= 0) throw new NumberFormatException();
                    } catch (NumberFormatException e) {
                        return "401 ERROR!";
                    }
                    Double currentBalance = userBalances.get(currentCard);
                    if (currentBalance == null) return "401 ERROR!";
                    if (currentBalance >= amount) {
                        userBalances.put(currentCard, currentBalance - amount);
                        saveBalances();
                        return "525 OK!";
                    } else {
                        return "401 ERROR!";
                    }
                case "QUIT":
                    authenticated = false;
                    currentCard = null;
                    return "BYE";
                default:
                    return "401 ERROR!";
            }
        }
    }
}


