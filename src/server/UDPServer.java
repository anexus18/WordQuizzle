package server;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

import static commons.Constants.*;
import static server.Consts.*;
import static server.UserDBExceptions.*;

/**
 * Class that handles the UDP socket, it keeps waiting for
 * incoming messages and splits them depending on the request
 */
class UDPServer extends Thread {

    /*
        Stores the challenges waiting to be accepted

        The key is the address of the user who have been challenged, the value
        is the address of the user who started the challenge
     */
    private final ConcurrentHashMap<SocketAddress, SocketAddress> pendingChallenges = new ConcurrentHashMap<>();


    @Override
    public void run() {
        try (DatagramSocket socket = new DatagramSocket(SERVER_UDP_PORT)){

            socket.setSoTimeout(UDP_SERVER_TIMEOUT);

            byte[] buffer = new byte[MAX_MESSAGE_LENGTH];
            DatagramPacket request = new DatagramPacket(buffer, buffer.length);

            while (!interrupted()) {
                try {
                    //wait for challenge request
                    socket.receive(request);

                    //get message string
                    String message = new String(buffer, 0, request.getLength(), StandardCharsets.UTF_8);
                    String[] messageFragments = message.split(" ");

                    SocketAddress challengedAddress;
                    SocketAddress challengerAddress;

                    switch (messageFragments[0]) {
                        case REQUEST_CHALLENGE:
                            String challenger = messageFragments[1];
                            String challenged = messageFragments[2];

                            String errorMessage = null;//stores, eventually, the error message

                            try {
                                DatagramPacket challengePacket = UserDB.instance.challengeFriend(challenger, challenged, request.getPort());
                                socket.send(challengePacket);

                                challengedAddress = challengePacket.getSocketAddress();
                                challengerAddress = request.getSocketAddress();

                                pendingChallenges.put(challengedAddress, challengerAddress);

                            } catch (UserNotFoundException e) {
                                errorMessage = RESPONSE_USER_NOT_FOUND;
                            } catch (NotFriendsException e) {
                                errorMessage = RESPONSE_NOT_FRIENDS;
                            } catch (NotLoggedException e) {
                                errorMessage = RESPONSE_NOT_LOGGED;
                            } catch (SameUserException e) {
                                errorMessage = RESPONSE_SAME_USER;
                            }

                            //there was an error
                            if (errorMessage != null) {
                                sendErrorMessage(socket, errorMessage, request.getAddress(), request.getPort());
                            }
                            break;

                        case CHALLENGE_OK:
                            //retrieve user addresses involved in the challenge
                            challengedAddress = request.getSocketAddress();
                            challengerAddress = pendingChallenges.get(challengedAddress);
                            if (challengerAddress == null) {
                                sendErrorMessage(socket, RESPONSE_CHALLENGE_TIMEOUT, challengedAddress);
                                continue;
                            }
                            pendingChallenges.remove(challengedAddress);

                            try {
                                //send ok message to both user
                                byte[] confirmationResponse = UserDB.instance.getChallengeConfirm(challengerAddress, challengedAddress);

                                DatagramPacket challengeConfirmationPacket = new DatagramPacket(confirmationResponse, confirmationResponse.length, challengedAddress);
                                socket.send(challengeConfirmationPacket);
                                challengeConfirmationPacket.setSocketAddress(challengerAddress);
                                socket.send(challengeConfirmationPacket);

                            } catch (ChallengeRequestTimeoutException e) {
                                sendErrorMessage(socket, RESPONSE_CHALLENGE_TIMEOUT, challengerAddress);
                                sendErrorMessage(socket, RESPONSE_CHALLENGE_TIMEOUT, challengerAddress);
                            } catch (UserNotFoundException e) {
                                sendErrorMessage(socket, RESPONSE_CHALLENGE_REFUSED, challengerAddress);
                                sendErrorMessage(socket, RESPONSE_CHALLENGE_REFUSED, challengerAddress);
                            }
                            break;

                        case CHALLENGE_REFUSED:
                            challengedAddress = request.getSocketAddress();
                            challengerAddress = pendingChallenges.get(challengedAddress);
                            if (challengerAddress == null) {
                                sendErrorMessage(socket, RESPONSE_CHALLENGE_TIMEOUT, challengedAddress);
                                continue;
                            }
                            pendingChallenges.remove(challengedAddress);

                            DatagramPacket challengeRefusedPacket = UserDB.instance.discardChallenge(challengerAddress, challengedAddress);
                            socket.send(challengeRefusedPacket);

                            break;


                        default:
                            //wrong request
                            sendErrorMessage(socket, RESPONSE_UNKNOWN_REQUEST, request.getAddress(), request.getPort());
                            break;
                    }
                } catch (SocketTimeoutException ignored) {
                    //no messages retrieved, continue
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        } catch (SocketException e) {
            e.printStackTrace();
        }
    }

    /**
     * Sends an error message through the UDP socket to the given address
     * @param datagramSocket The UDP socket
     * @param errorMessage The error message to be sent
     * @param address The socket address to send the error to
     */
    private void sendErrorMessage(DatagramSocket datagramSocket, String errorMessage, SocketAddress address) throws IOException {
        byte[] response = errorMessage.getBytes(StandardCharsets.UTF_8);
        DatagramPacket errorPacket = new DatagramPacket(response, response.length, address);
        datagramSocket.send(errorPacket);
    }

    /**
     * Sends an error message through the UDP socket to the given address
     * @param datagramSocket The UDP socket
     * @param errorMessage The error message to be sent
     * @param address The inet address to  send the message to
     * @param port The port of the address
     */
    private void sendErrorMessage(DatagramSocket datagramSocket, String errorMessage, InetAddress address, int port) throws IOException {
        byte[] response = errorMessage.getBytes(StandardCharsets.UTF_8);
        DatagramPacket errorPacket = new DatagramPacket(response, response.length, address, port);
        datagramSocket.send(errorPacket);
    }


}
