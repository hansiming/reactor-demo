package com.cszjo.nio;

import java.util.Scanner;

public class Base64Example {

    public static void main(String[] args) {
        Server server = new Server(8080);
        server.start();

        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        Scanner sc = new Scanner(System.in);
        String line;
        while ((line = sc.nextLine()) != null) {
            if (line.equals("stop")) {
                server.close();
                break;
            }

            Client client = new Client("localhost", 8080);
            String base64 = client.base64(line);
            System.out.println("result : " + base64);
        }
    }
}
