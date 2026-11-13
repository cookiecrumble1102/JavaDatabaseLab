import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;
import javax.swing.border.LineBorder;
import javax.swing.table.DefaultTableModel;

// Класс графического интерфейса наследуется от JFrame
public class DatabaseGUI extends JFrame {

    private JTextField queryField;
    private JTable resultTable;
    private JTextArea statusArea;
    private JComboBox<String> historyCombo;
    private DefaultTableModel tableModel;

    // Переменные для работы с бэкендом
    private DatabaseManager dbManager;
    private SQLParser sqlParser;

    // Палитра цветов
    private final Color bgColor = new Color(30, 30, 46);
    private final Color panelColor = new Color(49, 50, 68);
    private final Color textColor = new Color(205, 214, 244);
    private final Color accentColor = new Color(65, 105, 225);
    private final Color successColor = new Color(166, 227, 161);
    private final Color errorColor = new Color(243, 139, 168);

    public DatabaseGUI() {
        setTitle("СУБД");
        setSize(1000, 650);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout(5, 5));
        getContentPane().setBackground(bgColor);

        //Инициализация
        try {
            dbManager = new DatabaseManager("data"); // Папка с файлами БД
            sqlParser = new SQLParser(dbManager);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Ошибка инициализации БД: " + e.getMessage());
        }


        


        //Верхняя панель (Ввод)
        JPanel topPanel = new JPanel(new BorderLayout(10, 10));
        topPanel.setBackground(bgColor);
        topPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 0, 10));

        // Обертка для истории, чтобы она не растягивалась
        JPanel historyWrapper = new JPanel(new BorderLayout());
        historyWrapper.setBackground(bgColor);
        historyCombo = new JComboBox<>();
        historyCombo.setBackground(panelColor);
        historyCombo.setForeground(textColor);
        historyCombo.setPreferredSize(new Dimension(130, 35));
        historyCombo.addItem("История...");
        historyCombo.addActionListener(e -> {
            if (historyCombo.getSelectedIndex() > 0) {
                queryField.setText(historyCombo.getSelectedItem().toString());
            }
        });
        historyWrapper.add(historyCombo, BorderLayout.CENTER);

        queryField = new JTextField();
        queryField.setBackground(panelColor);
        queryField.setForeground(textColor);
        queryField.setCaretColor(accentColor);
        queryField.setFont(new Font("Consolas", Font.PLAIN, 14));
        queryField.setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(new Color(69, 71, 90), 1),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)
        ));

        JButton executeBtn = new JButton("Выполнить"); 
        executeBtn.setBackground(accentColor);
        executeBtn.setForeground(Color.WHITE);
        executeBtn.setFocusPainted(false);
        executeBtn.setFont(new Font("Arial", Font.BOLD, 14));
        executeBtn.setBorder(BorderFactory.createEmptyBorder(10, 20, 10, 20));
        executeBtn.setCursor(new Cursor(Cursor.HAND_CURSOR));

        JPanel inputPanel = new JPanel(new BorderLayout(5, 0));
        inputPanel.setBackground(bgColor);
        inputPanel.add(queryField, BorderLayout.CENTER);
        inputPanel.add(executeBtn, BorderLayout.EAST);


        topPanel.add(historyWrapper, BorderLayout.WEST);
        topPanel.add(inputPanel, BorderLayout.CENTER);

        // Очистка
        JButton clearBtn = new JButton("Очистить");
        clearBtn.setBackground(errorColor);
        clearBtn.setForeground(bgColor);
        clearBtn.setFocusPainted(false);
        clearBtn.addActionListener(e -> {
            queryField.setText("");
            tableModel.setRowCount(0);
            statusArea.setText("Окно очищено.");
            statusArea.setForeground(textColor);
        });

        JPanel rightButtonsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        rightButtonsPanel.setBackground(bgColor);
        rightButtonsPanel.add(clearBtn);
        inputPanel.add(rightButtonsPanel, BorderLayout.EAST);

        //Таблица(Центр)
        tableModel = new DefaultTableModel();
        resultTable = new JTable(tableModel);
        resultTable.setBackground(panelColor);
        resultTable.setForeground(textColor);
        resultTable.setGridColor(new Color(69, 71, 90));
        resultTable.setRowHeight(30);
        resultTable.setFont(new Font("Arial", Font.PLAIN, 14));
        
        resultTable.getTableHeader().setBackground(new Color(24, 24, 37));
        resultTable.getTableHeader().setForeground(accentColor);
        resultTable.getTableHeader().setFont(new Font("Arial", Font.BOLD, 14));
        resultTable.setSelectionBackground(accentColor); 
        resultTable.setSelectionForeground(Color.WHITE);
        
        JScrollPane scrollPane = new JScrollPane(resultTable);
        scrollPane.getViewport().setBackground(bgColor);
        scrollPane.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));

        //Статус(Низ)
        statusArea = new JTextArea(3, 50);
        statusArea.setBackground(new Color(24, 24, 37));
        statusArea.setForeground(successColor);
        statusArea.setEditable(false);
        statusArea.setFont(new Font("Consolas", Font.PLAIN, 13));
        statusArea.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
        
        JScrollPane statusScroll = new JScrollPane(statusArea);
        statusScroll.setBorder(BorderFactory.createEmptyBorder(0, 10, 10, 10));

        //Размещение элементов
        add(topPanel, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
        add(statusScroll, BorderLayout.SOUTH);

        //Обработчики кнопок
        executeBtn.addActionListener(e -> executeQuery(queryField.getText()));
        queryField.addActionListener(e -> executeQuery(queryField.getText()));
    }


    //Главный метод связки с остальными файлами
    private void executeQuery(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            statusArea.setForeground(errorColor);
            statusArea.setText("Ошибка: Введите запрос.");
            return;
        }

        // Добавляем в историю
        if (((DefaultComboBoxModel<String>)historyCombo.getModel()).getIndexOf(sql) == -1) {
            historyCombo.addItem(sql);
        }

        try {
            //Вызов парсера
            String resultText = sqlParser.execute(sql);
            
            statusArea.setForeground(successColor);
            statusArea.setText("Успешно. Ответ сервера:\n" + resultText);

            // Если это SELECT, пытаемся нарисовать таблицу
            if (sql.trim().toUpperCase().startsWith("SELECT") && resultText.contains("|")) {
                parseAndShowTable(resultText);
            }
            
        } catch (Exception ex) {
            statusArea.setForeground(errorColor);
            statusArea.setText("Ошибка при выполнении: " + ex.getMessage());
        }
    }

    // Метод, который превращает текстовый ответ парсера в красивую таблицу
    private void parseAndShowTable(String resultText) {
        String[] lines = resultText.trim().split("\n");
        int headerIndex = -1;

        // Ищем строку, которая начинается с '|' (это заголовки)
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().startsWith("|")) {
                headerIndex = i;
                break;
            }
        }

        if (headerIndex == -1) return; 

        // Разбираем заголовки
        String[] headerParts = lines[headerIndex].split("\\|");
        List<String> columnsList = new ArrayList<>();
        for (String part : headerParts) {
            if (!part.trim().isEmpty()) {
                columnsList.add(part.trim());
            }
        }
        String[] columns = columnsList.toArray(new String[0]);

        // Разбираем строки данных
        List<Object[]> dataList = new ArrayList<>();
        for (int i = headerIndex + 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (!line.startsWith("|")) break; // Таблица закончилась
            
            String[] splitLine = line.split("\\|", -1);
            Object[] rowData = new Object[columns.length];
            for (int j = 1; j <= columns.length; j++) {
                if (j < splitLine.length) {
                    rowData[j - 1] = splitLine[j].trim();
                } else {
                    rowData[j - 1] = "";
                }
            }
            dataList.add(rowData);
        }

        // Преобразуем в формат JTable
        Object[][] data = new Object[dataList.size()][columns.length];
        for (int i = 0; i < dataList.size(); i++) {
            data[i] = dataList.get(i);
        }

        updateTable(data, columns);
    }

    public void updateTable(Object[][] data, String[] columns) {
        tableModel.setDataVector(data, columns);
    }

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        } catch (Exception e) {
            e.printStackTrace();
        }

        SwingUtilities.invokeLater(() -> {
            DatabaseGUI gui = new DatabaseGUI();
            gui.setVisible(true);
        });
    }
}