package com.cszjo.nio;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;

public class Client {

    private Socket socket;

    public Client(String ip, int port) {

        try {
            this.socket = new Socket(ip, port);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public String base64(String originStr) {
        try {
            DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
            byte[] msg = originStr.getBytes();
            dos.writeInt(msg.length);
            dos.write(msg);
            dos.flush();
        } catch (IOException e) {
            throw new RuntimeException("Failed to send request to server!", e);
        }

        try {
            DataInputStream dis = new DataInputStream(socket.getInputStream());
            int msgLength = dis.readInt();
            byte[] msg = new byte[msgLength];
            dis.read(msg);
            return new String(msg);
        } catch (IOException e) {
            throw new RuntimeException("Failed to receive request from server!", e);
        }
    }

    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
            System.out.println("Failed to close socket!");
        }
    }

}
