package com.example.agent;

import com.google.genai.types.Content;
import java.util.List;
import java.util.Scanner;

/** Run this class to chat with the agent. */
public final class Main {
    public static void main(String[] args) {
        Agent agent;
        try {
            agent = new Agent();
        } catch (IllegalStateException e) {
            System.err.println(e.getMessage());
            return;
        }

        List<Content> messages = agent.newConversation(); // memory lives here across turns
        System.out.println("Agent ready. Type 'quit' to exit.");
        System.out.println(
                "Try: Should I visit Manali this weekend? 2 people, budget 6000 total. Save a summary.\n");

        try (Scanner in = new Scanner(System.in)) {
            while (true) {
                System.out.print("You: ");
                if (!in.hasNextLine()) {
                    break;
                }
                String goal = in.nextLine().strip();
                if (goal.isEmpty()) {
                    continue;
                }
                if (goal.equalsIgnoreCase("quit") || goal.equalsIgnoreCase("exit")) {
                    break;
                }
                String answer = agent.run(goal, messages);
                System.out.println("\nAgent: " + answer + "\n");
            }
        }
    }
}
