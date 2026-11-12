import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.io.File;
import java.io.IOException;



//　** Кирилл, добрый вечер, сразу предупреждаю, что 99% методов DataTable выбрасывают какие-то исключения
// Так что постарайся смотреть на сигнатуры и обрабатывать приходящие оттуда, а то оно поломает всю логику
// Постарался накидать все заглушки для методов, о которых вспомнил. Надеюсь, что был полезен.

// Я не знаю, что еще сюда добавить, поэтому будет анекдот:
//
// - Что будет, если в пустыне Сахара объявить социализм?
// - Первые три года ничего, а потом начнутся перебои с песком...

// спасите ^-^


public class DatabaseManager 
{
    // Пользовательская ошибка или предупреждение
    // Перебрасывается для дальнейшей обработки парсером/графическим интерфейсом
    public class UserException extends RuntimeException
    {
        public UserException(String message) 
        {
            super(message);
        }
    }

    // Ошибка, предупреждающая о критической неисправности в системе баз данных
    // Требует от среды управления БД экстренного завершения
    public class CriticalException extends RuntimeException
    {
        public CriticalException(String message) 
        {
            super(message);
        }
    }
    ArrayList<DataTable> tables;
    String directoryPath;

    // По дефолту вызывается конструктор с заданной директорией работы (при запуске программы, использующей наш класс)
    public DatabaseManager(String directoryPath)
    {
        this.tables = new ArrayList<>();
        File dir = new File(directoryPath);

        // Если папка существует
        if (!dir.exists() || !dir.isDirectory())
        {
            dir.mkdirs();
        }

        this.directoryPath = directoryPath;
    }

    // CREATE TABLE
    public void createTable(String dbName, ArrayList<Column> columns)
    {
        try
        {
            String dbFilePath = directoryPath + File.separator + dbName + ".db";
            String indexFilePath = directoryPath + File.separator + dbName + "_" + columns.get(0).getName() + ".idx";
            File dbFile = new File(dbFilePath);
            File indexFile = new File(indexFilePath);


            // Если нет файлов БД, то создаем их, иначе БД будет считать, что файлы уже есть
            if (!dbFile.exists()) {dbFile.createNewFile();}
            if (!indexFile.exists()) {indexFile.createNewFile();}

            // Здесь желательно сделать индексацию по тем полям, для которых уже есть файл индекса (загрузить в оперативку :3)
            // Т.е цикл по колонкам и проверка на создание файла, если есть -> делать loadIndex(), иначе скип

            DataTable table = new DataTable(this.directoryPath, dbName, columns);
            this.tables.add(table);

        }
        catch (IllegalArgumentException e)
        {
            throw new UserException("Не удалось создать таблицу " + dbName + "!" + e.getLocalizedMessage());
        }
        catch (IOException e)
        {
            throw new CriticalException(e.getLocalizedMessage());
        }
    }

    // DROP TABLE
    public void dropTable(String dbName) {}


    // SEARCH
    // В качестве value передается либо значение, которое нужно найти, либо объект класса Range
    public HashSet<Record> searchTable(Column column, Object value) throws UserException {return null;} 

    // INSERT INTO
    public void insertIntoTable(HashMap<String, Object> values) throws UserException {}

    // UPDATE
    public void insertIntoTable(Column column, Object value) throws UserException {}

    // DELETE
    public void deleteFromTable(Column column, Object value) throws UserException {}

    // CREATE INDEX
    // 1) вызывает приватную FILL INDEX
    // 2) затем запускает loadIndex для дбшки

    // private FILL INDEX
    // создает и заполняет индексный файл
    

}