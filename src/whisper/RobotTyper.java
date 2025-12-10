package Whisper;

import java.awt.AWTException;
import java.awt.Robot;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.swing.JFrame;
import javax.swing.JTextField;

/**
 * Utility class to type strings using the Robot class with automatic keyboard layout detection
 * (AZERTY/QWERTY) based on system locale.
 */
public class RobotTyper {

    private Robot robot;
    private boolean isAzerty;
    private Map<Character, Integer> qwertyKeyMap;
    private Map<Character, Integer> azertyKeyMap;
    private Map<Character, KeyCombo> qwertySpecialChars;
    private Map<Character, KeyCombo> azertySpecialChars;

    /**
     * Inner class to represent key combinations with modifiers
     */
    private static class KeyCombo {
        final int modifier;
        final int keyCode;

        KeyCombo(int modifier, int keyCode) {
            this.modifier = modifier;
            this.keyCode = keyCode;
        }
    }

    /**
     * Constructor - initializes Robot and detects keyboard layout
     * 
     * @throws AWTException
     */
    public RobotTyper() throws AWTException {
        this.robot = new Robot();
        initializeKeyMaps();
        detectKeyboardLayout();
    }

    /**
     * Initialize key mappings for both QWERTY and AZERTY layouts
     */
    private void initializeKeyMaps() {
        // Initialize QWERTY key map
        this.qwertyKeyMap = new HashMap<>();

        // Letters
        this.qwertyKeyMap.put('a', KeyEvent.VK_A);
        this.qwertyKeyMap.put('b', KeyEvent.VK_B);
        this.qwertyKeyMap.put('c', KeyEvent.VK_C);
        this.qwertyKeyMap.put('d', KeyEvent.VK_D);
        this.qwertyKeyMap.put('e', KeyEvent.VK_E);
        this.qwertyKeyMap.put('f', KeyEvent.VK_F);
        this.qwertyKeyMap.put('g', KeyEvent.VK_G);
        this.qwertyKeyMap.put('h', KeyEvent.VK_H);
        this.qwertyKeyMap.put('i', KeyEvent.VK_I);
        this.qwertyKeyMap.put('j', KeyEvent.VK_J);
        this.qwertyKeyMap.put('k', KeyEvent.VK_K);
        this.qwertyKeyMap.put('l', KeyEvent.VK_L);
        this.qwertyKeyMap.put('m', KeyEvent.VK_M);
        this.qwertyKeyMap.put('n', KeyEvent.VK_N);
        this.qwertyKeyMap.put('o', KeyEvent.VK_O);
        this.qwertyKeyMap.put('p', KeyEvent.VK_P);
        this.qwertyKeyMap.put('q', KeyEvent.VK_Q);
        this.qwertyKeyMap.put('r', KeyEvent.VK_R);
        this.qwertyKeyMap.put('s', KeyEvent.VK_S);
        this.qwertyKeyMap.put('t', KeyEvent.VK_T);
        this.qwertyKeyMap.put('u', KeyEvent.VK_U);
        this.qwertyKeyMap.put('v', KeyEvent.VK_V);
        this.qwertyKeyMap.put('w', KeyEvent.VK_W);
        this.qwertyKeyMap.put('x', KeyEvent.VK_X);
        this.qwertyKeyMap.put('y', KeyEvent.VK_Y);
        this.qwertyKeyMap.put('z', KeyEvent.VK_Z);

        // Numbers
        this.qwertyKeyMap.put('0', KeyEvent.VK_0);
        this.qwertyKeyMap.put('1', KeyEvent.VK_1);
        this.qwertyKeyMap.put('2', KeyEvent.VK_2);
        this.qwertyKeyMap.put('3', KeyEvent.VK_3);
        this.qwertyKeyMap.put('4', KeyEvent.VK_4);
        this.qwertyKeyMap.put('5', KeyEvent.VK_5);
        this.qwertyKeyMap.put('6', KeyEvent.VK_6);
        this.qwertyKeyMap.put('7', KeyEvent.VK_7);
        this.qwertyKeyMap.put('8', KeyEvent.VK_8);
        this.qwertyKeyMap.put('9', KeyEvent.VK_9);

        // Common keys
        this.qwertyKeyMap.put(' ', KeyEvent.VK_SPACE);
        this.qwertyKeyMap.put('\n', KeyEvent.VK_ENTER);
        this.qwertyKeyMap.put('\t', KeyEvent.VK_TAB);
        this.qwertyKeyMap.put('.', KeyEvent.VK_PERIOD);
        this.qwertyKeyMap.put(',', KeyEvent.VK_COMMA);
        this.qwertyKeyMap.put('-', KeyEvent.VK_MINUS);
        this.qwertyKeyMap.put('=', KeyEvent.VK_EQUALS);
        this.qwertyKeyMap.put('/', KeyEvent.VK_SLASH);
        this.qwertyKeyMap.put('\\', KeyEvent.VK_BACK_SLASH);
        this.qwertyKeyMap.put(';', KeyEvent.VK_SEMICOLON);
        this.qwertyKeyMap.put('\'', KeyEvent.VK_QUOTE);
        this.qwertyKeyMap.put('[', KeyEvent.VK_OPEN_BRACKET);
        this.qwertyKeyMap.put(']', KeyEvent.VK_CLOSE_BRACKET);
        this.qwertyKeyMap.put('`', KeyEvent.VK_BACK_QUOTE);

        // Initialize QWERTY special characters (with Shift modifier)
        this.qwertySpecialChars = new HashMap<>();
        this.qwertySpecialChars.put('!', new KeyCombo(KeyEvent.VK_SHIFT, KeyEvent.VK_1));
        this.qwertySpecialChars.put('@', new KeyCombo(KeyEvent.VK_SHIFT, KeyEvent.VK_2));
        this.qwertySpecialChars.put('#', new KeyCombo(KeyEvent.VK_SHIFT, KeyEvent.VK_3));
        this.qwertySpecialChars.put('$', new KeyCombo(KeyEvent.VK_SHIFT, KeyEvent.VK_4));
        this.qwertySpecialChars.put('%', new KeyCombo(KeyEvent.VK_SHIFT, KeyEvent.VK_5));
        this.qwertySpecialChars.put('^', new KeyCombo(KeyEvent.VK_SHIFT, KeyEvent.VK_6));
        this.qwertySpecialChars.put('&', new KeyCombo(KeyEvent.VK_SHIFT, KeyEvent.VK_7));
        this.qwertySpecialChars.put('*', new KeyCombo(KeyEvent.VK_SHIFT, KeyEvent.VK_8));
        this.qwertySpecialChars.put('(', new KeyCombo(KeyEvent.VK_SHIFT, KeyEvent.VK_9));
        this.qwertySpecialChars.put(')', new KeyCombo(KeyEvent.VK_SHIFT, KeyEvent.VK_0));
        this.qwertySpecialChars.put('_', new KeyCombo(KeyEvent.VK_SHIFT, KeyEvent.VK_MINUS));
        this.qwertySpecialChars.put('+', new KeyCombo(KeyEvent.VK_SHIFT, KeyEvent.VK_EQUALS));
        this.qwertySpecialChars.put('{', new KeyCombo(KeyEvent.VK_SHIFT, KeyEvent.VK_OPEN_BRACKET));
        this.qwertySpecialChars.put('}', new KeyCombo(KeyEvent.VK_SHIFT, KeyEvent.VK_CLOSE_BRACKET));
        this.qwertySpecialChars.put('|', new KeyCombo(KeyEvent.VK_SHIFT, KeyEvent.VK_BACK_SLASH));
        this.qwertySpecialChars.put(':', new KeyCombo(KeyEvent.VK_SHIFT, KeyEvent.VK_SEMICOLON));
        this.qwertySpecialChars.put('"', new KeyCombo(KeyEvent.VK_SHIFT, KeyEvent.VK_QUOTE));
        this.qwertySpecialChars.put('<', new KeyCombo(KeyEvent.VK_SHIFT, KeyEvent.VK_COMMA));
        this.qwertySpecialChars.put('>', new KeyCombo(KeyEvent.VK_SHIFT, KeyEvent.VK_PERIOD));
        this.qwertySpecialChars.put('?', new KeyCombo(KeyEvent.VK_SHIFT, KeyEvent.VK_SLASH));
        this.qwertySpecialChars.put('~', new KeyCombo(KeyEvent.VK_SHIFT, KeyEvent.VK_BACK_QUOTE));

        // Initialize AZERTY key map
        this.azertyKeyMap = new HashMap<>();

        // Letters (mostly the same as QWERTY but we'll define them again for clarity)
        this.azertyKeyMap.put('a', KeyEvent.VK_A);
        this.azertyKeyMap.put('b', KeyEvent.VK_B);
        this.azertyKeyMap.put('c', KeyEvent.VK_C);
        this.azertyKeyMap.put('d', KeyEvent.VK_D);
        this.azertyKeyMap.put('e', KeyEvent.VK_E);
        this.azertyKeyMap.put('f', KeyEvent.VK_F);
        this.azertyKeyMap.put('g', KeyEvent.VK_G);
        this.azertyKeyMap.put('h', KeyEvent.VK_H);
        this.azertyKeyMap.put('i', KeyEvent.VK_I);
        this.azertyKeyMap.put('j', KeyEvent.VK_J);
        this.azertyKeyMap.put('k', KeyEvent.VK_K);
        this.azertyKeyMap.put('l', KeyEvent.VK_L);
        this.azertyKeyMap.put('m', KeyEvent.VK_M);
        this.azertyKeyMap.put('n', KeyEvent.VK_N);
        this.azertyKeyMap.put('o', KeyEvent.VK_O);
        this.azertyKeyMap.put('p', KeyEvent.VK_P);
        this.azertyKeyMap.put('q', KeyEvent.VK_Q);
        this.azertyKeyMap.put('r', KeyEvent.VK_R);
        this.azertyKeyMap.put('s', KeyEvent.VK_S);
        this.azertyKeyMap.put('t', KeyEvent.VK_T);
        this.azertyKeyMap.put('u', KeyEvent.VK_U);
        this.azertyKeyMap.put('v', KeyEvent.VK_V);
        this.azertyKeyMap.put('w', KeyEvent.VK_W);
        this.azertyKeyMap.put('x', KeyEvent.VK_X);
        this.azertyKeyMap.put('y', KeyEvent.VK_Y);
        this.azertyKeyMap.put('z', KeyEvent.VK_Z);

        // AZERTY differences - main keys
        this.azertyKeyMap.put('&', KeyEvent.VK_1);
        this.azertyKeyMap.put('é', KeyEvent.VK_2);
        this.azertyKeyMap.put('"', KeyEvent.VK_3);
        this.azertyKeyMap.put('\'', KeyEvent.VK_4);
        this.azertyKeyMap.put('(', KeyEvent.VK_5);
        this.azertyKeyMap.put(')', KeyEvent.VK_RIGHT_PARENTHESIS);
        this.azertyKeyMap.put('-', KeyEvent.VK_6);
        this.azertyKeyMap.put('è', KeyEvent.VK_7);
        this.azertyKeyMap.put('_', KeyEvent.VK_8);
        this.azertyKeyMap.put('ç', KeyEvent.VK_9);
        this.azertyKeyMap.put('à', KeyEvent.VK_0);
        this.azertyKeyMap.put('!', KeyEvent.VK_EXCLAMATION_MARK);
        this.azertyKeyMap.put(':', KeyEvent.VK_COLON);
        this.azertyKeyMap.put('=', KeyEvent.VK_EQUALS);
        // FIXME ??
        this.azertyKeyMap.put('ù', KeyEvent.VK_U);
        // FIXME ??
        this.azertyKeyMap.put('¨', KeyEvent.VK_U);

        // Common keys
        this.azertyKeyMap.put(' ', KeyEvent.VK_SPACE);
        this.azertyKeyMap.put('\n', KeyEvent.VK_ENTER);
        this.azertyKeyMap.put('\t', KeyEvent.VK_TAB);
        this.azertyKeyMap.put(',', KeyEvent.VK_COMMA); // In AZERTY, comma is on M key
        this.azertyKeyMap.put(';', KeyEvent.VK_SEMICOLON); // Semicolon is on comma key

        // Initialize AZERTY special characters
        this.azertySpecialChars = new HashMap<>();
        this.azertySpecialChars.put('1', new KeyCombo(KeyEvent.VK_SHIFT, KeyEvent.VK_1));
        this.azertySpecialChars.put('2', new KeyCombo(KeyEvent.VK_SHIFT, KeyEvent.VK_2));
        this.azertySpecialChars.put('3', new KeyCombo(KeyEvent.VK_SHIFT, KeyEvent.VK_3));
        this.azertySpecialChars.put('4', new KeyCombo(KeyEvent.VK_SHIFT, KeyEvent.VK_4));
        this.azertySpecialChars.put('5', new KeyCombo(KeyEvent.VK_SHIFT, KeyEvent.VK_5));
        this.azertySpecialChars.put('6', new KeyCombo(KeyEvent.VK_SHIFT, KeyEvent.VK_6));
        this.azertySpecialChars.put('7', new KeyCombo(KeyEvent.VK_SHIFT, KeyEvent.VK_7));
        this.azertySpecialChars.put('8', new KeyCombo(KeyEvent.VK_SHIFT, KeyEvent.VK_8));
        this.azertySpecialChars.put('9', new KeyCombo(KeyEvent.VK_SHIFT, KeyEvent.VK_9));
        this.azertySpecialChars.put('0', new KeyCombo(KeyEvent.VK_SHIFT, KeyEvent.VK_0));

        this.azertySpecialChars.put('@', new KeyCombo(KeyEvent.VK_ALT_GRAPH, KeyEvent.VK_0));
        this.azertySpecialChars.put('~', new KeyCombo(KeyEvent.VK_ALT_GRAPH, KeyEvent.VK_2));
        this.azertySpecialChars.put('#', new KeyCombo(KeyEvent.VK_ALT_GRAPH, KeyEvent.VK_3));
        this.azertySpecialChars.put('{', new KeyCombo(KeyEvent.VK_ALT_GRAPH, KeyEvent.VK_4));
        this.azertySpecialChars.put('[', new KeyCombo(KeyEvent.VK_ALT_GRAPH, KeyEvent.VK_5));
        this.azertySpecialChars.put('`', new KeyCombo(KeyEvent.VK_ALT_GRAPH, KeyEvent.VK_7));
        this.azertySpecialChars.put('|', new KeyCombo(KeyEvent.VK_ALT_GRAPH, KeyEvent.VK_6));
        this.azertySpecialChars.put('^', new KeyCombo(KeyEvent.VK_ALT_GRAPH, KeyEvent.VK_9));
        this.azertySpecialChars.put('\\', new KeyCombo(KeyEvent.VK_ALT_GRAPH, KeyEvent.VK_8));

        this.azertySpecialChars.put(']', new KeyCombo(KeyEvent.VK_ALT_GRAPH, KeyEvent.VK_RIGHT_PARENTHESIS));

        this.azertySpecialChars.put('}', new KeyCombo(KeyEvent.VK_ALT_GRAPH, KeyEvent.VK_EQUALS));
        this.azertySpecialChars.put('$', new KeyCombo(KeyEvent.VK_DOLLAR, 0));
        this.azertySpecialChars.put('£', new KeyCombo(KeyEvent.VK_ALT_GRAPH, KeyEvent.VK_DOLLAR));
        this.azertySpecialChars.put('¤', new KeyCombo(KeyEvent.VK_ALT_GRAPH, KeyEvent.VK_DOLLAR));
        this.azertySpecialChars.put('?', new KeyCombo(KeyEvent.VK_SHIFT, KeyEvent.VK_COMMA));
        this.azertySpecialChars.put('.', new KeyCombo(KeyEvent.VK_SHIFT, KeyEvent.VK_SEMICOLON));
        this.azertySpecialChars.put('/', new KeyCombo(KeyEvent.VK_SHIFT, KeyEvent.VK_COLON));
        this.azertySpecialChars.put('§', new KeyCombo(KeyEvent.VK_SHIFT, KeyEvent.VK_DOLLAR));
        this.azertySpecialChars.put('°', new KeyCombo(KeyEvent.VK_SHIFT, KeyEvent.VK_RIGHT_PARENTHESIS));
        this.azertySpecialChars.put('+', new KeyCombo(KeyEvent.VK_SHIFT, KeyEvent.VK_EQUALS));
        this.azertySpecialChars.put('*', new KeyCombo(KeyEvent.VK_ASTERISK, 0));
        this.azertySpecialChars.put('%', new KeyCombo(KeyEvent.VK_SHIFT, KeyEvent.VK_U));
        this.azertySpecialChars.put('µ', new KeyCombo(KeyEvent.VK_ALT_GRAPH, KeyEvent.VK_M));
        this.azertySpecialChars.put('<', new KeyCombo(KeyEvent.VK_LESS, 0));
        this.azertySpecialChars.put('>', new KeyCombo(KeyEvent.VK_SHIFT, KeyEvent.VK_LESS));
    }

    /**
     * Detect keyboard layout based on system locale
     */
    private void detectKeyboardLayout() {
        Locale locale = Locale.getDefault();
        String country = locale.getCountry();

        // Define countries that typically use AZERTY layout
        boolean isAzertyCountry = "FR".equalsIgnoreCase(country) || // France
                "BE".equalsIgnoreCase(country) || // Belgium
                "DZ".equalsIgnoreCase(country) || // Algeria
                "MA".equalsIgnoreCase(country) || // Morocco
                "TN".equalsIgnoreCase(country); // Tunisia

        this.isAzerty = isAzertyCountry;
        System.out.println("Detected keyboard layout: " + (this.isAzerty ? "AZERTY" : "QWERTY"));
    }

    /**
     * Manually set the keyboard layout
     */
    public void setAzertyLayout(boolean isAzerty) {
        this.isAzerty = isAzerty;
    }

    /**
     * Type a single character
     */
    private void typeChar(char c) {
        // Handle uppercase letters
        if (Character.isUpperCase(c)) {
            this.robot.keyPress(KeyEvent.VK_SHIFT);
            try {
                this.robot.keyPress(KeyEvent.VK_A + (c - 'A'));
                this.robot.keyRelease(KeyEvent.VK_A + (c - 'A'));
            } finally {
                this.robot.keyRelease(KeyEvent.VK_SHIFT);
            }
            return;
        }

        // Get appropriate key map based on keyboard layout
        Map<Character, Integer> currentKeyMap = this.isAzerty ? this.azertyKeyMap : this.qwertyKeyMap;
        Map<Character, KeyCombo> currentSpecialChars = this.isAzerty ? this.azertySpecialChars : this.qwertySpecialChars;

        // Check if the character is in the direct mapping
        Integer keyCode = currentKeyMap.get(c);
        if (keyCode != null) {
            this.robot.keyPress(keyCode);
            this.robot.keyRelease(keyCode);
            return;
        }

        // Check if the character requires a key combination
        KeyCombo keyCombo = currentSpecialChars.get(c);
        if (keyCombo != null) {
            if (keyCombo.modifier != 0) {
                if (keyCombo.modifier == KeyEvent.VK_ALT_GRAPH) {
                    robot.keyPress(KeyEvent.VK_CONTROL);
                    robot.keyPress(KeyEvent.VK_ALT);
                } else {
                    this.robot.keyPress(keyCombo.modifier);
                }
            }
            try {
                if (keyCombo.keyCode != 0) {
                    this.robot.keyPress(keyCombo.keyCode);
                    this.robot.keyRelease(keyCombo.keyCode);
                }
            } finally {
                if (keyCombo.modifier != 0) {
                    if (keyCombo.modifier == KeyEvent.VK_ALT_GRAPH) {
                        robot.keyRelease(KeyEvent.VK_ALT);
                        robot.keyRelease(KeyEvent.VK_CONTROL);
                    } else {
                        this.robot.keyRelease(keyCombo.modifier);
                    }
                }
            }
            return;
        }

        // Handle characters not explicitly mapped
        // For specific accented characters, we could implement
        // dead key combinations here, but this is a simplified version
        System.out.println("Character not mapped: " + c);
    }

    /**
     * Type a string with a delay between each character
     */
    public void typeString(String text, int delayMillis) {
        if (text == null) {
            return;
        }

        final int length = text.length();
        for (int i = 0; i < length; i++) {
            try {
                typeChar(text.charAt(i));

                if (delayMillis > 0) {
                    this.robot.delay(delayMillis);
                }
            } catch (Exception e) {
                System.out.println("ERROR char : " + text.charAt(i));
                e.printStackTrace();
            }
        }
    }

    /**
     * Type a string with a default delay
     */
    public void typeString(String text) {
        typeString(text, 10); // Default 10ms delay between keystrokes
    }

    /**
     * Main method for testing
     */
    public static void main(String[] args) {
        try {
            // Give the user time to focus on the target input field
            System.out.println("Focus on your target input field. Typing will begin in 5 seconds...");
            JFrame f = new JFrame();
            final JTextField contentPane = new JTextField(20);
            f.setContentPane(contentPane);
            contentPane.addKeyListener(new KeyListener() {

                @Override
                public void keyTyped(KeyEvent e) {
                    // TODO Auto-generated method stub

                }

                @Override
                public void keyReleased(KeyEvent e) {
                    System.out.println("RobotTyper.main(...).new KeyListener() {...}.keyReleased()" + e);

                }

                @Override
                public void keyPressed(KeyEvent e) {
                    // TODO Auto-generated method stub

                }
            });
            f.setVisible(true);
            Thread.sleep(3000);

            RobotTyper typer = new RobotTyper();

            // Test string including special characters
            String testString = "Hello World! This is a test. Ah, 123456790; : /\\ ù µ* ¨^ £$¤ +=} °)] &é\"'(-è_çà)= !@# $%^&*  ()[]={}";

            // Type the test string
            typer.typeString(testString, 1); // 50ms delay for easy viewing

        } catch (AWTException | InterruptedException e) {
            e.printStackTrace();
        }
    }
}
