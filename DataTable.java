// Класс, в котором хранится информация о конкретной базе данных
// Через него получаем доступ к хранимой информации, тут происходит все хеширование и здесь же выполняется основной функционал

import java.util.HashMap;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;


// Важные комменты пометил **, чтобы не забывать. От них зависит жизнеспособность этого кода, т.к в них прописаны нюансы, забыв которые, можно все безвозвратно положить в будущем.
public class DataTable
{
    // Кастомные ошибки

    // Общая ошибка в базе данных
    public class DatabaseException extends RuntimeException
    {
        public DatabaseException(String message) 
        {
            super(message);
        }
    }


    // Ошибка операции в базе данных (вставки, удаления и т.д)
    // Не ведет к аварийному завершению программы и позволяет пользователю самостоятельно решить проблему 
    // - невыполнимый запрос
    // - нарушение уникальности первичного ключа 
    // - опечатка на стороне пользователя 
    public class DatabaseOperationException extends DatabaseException
    {
        public DatabaseOperationException(String message) 
        {
            super(message);
        }
    }

    // Ошибка обращения к файлу индексов по ключевому полю
    // !! Cигнализирует о необходимости совершить перезапись индексного файла для данной БД
    public class PrimaryKeyIntegrityException extends RuntimeException 
    {
        public PrimaryKeyIntegrityException(String message) 
        {
            super(message);
        }
    }


    // Ошибка индексации по ключевому полю
    // !! Cигнализирует о ключевой ошибке и требует аварийного завершения всей программы на уровне менеджера баз данных
    public class PrimaryKeyIndexException extends RuntimeException 
    {
        public PrimaryKeyIndexException(String message) 
        {
            super(message);
        }
    }


    // Константы

    // ** В файле перед каждым полем (кроме ключевого поля) лежит бит, показывающий наличие или отсутствие этого поля в записи (где false - отсутствие)**
    private final int DBRECORDSIZE;
    private String KEYFIELDNAME = "";

    // Шаблон полей
    private ArrayList<Column> columns;

    // Файлы
    
    // Файлы ДБшки
    private final String DBFILEPATH;

    // Индексные файлы в формате [имя столбца : путь к файлу]
    private HashMap<String, String> INDEXFILES;

    // Хэш-объекты
    private HashMap<String, Object> hashes; 
    // Храним [имя: хеш-таблицу со смещениями в файле для получения нужного ключа]
    // Object здесь стоит, т.к у нас будут использоваться как обычные HashMap, так и TreeMap (для чисел и обработки условий WHERE)


    // Создаем базу данных на основе пути к директории, имени и упорядоченного набора объектов Column
    public DataTable(String directoryPath, String name, ArrayList<Column> columns) throws IllegalArgumentException
    {
        // Файлы предварительно созданы в Database Manager и их валидность никак не проверяется этим классом, т.к он не отвечает за создание файлов
        // В качестве исключения делается проверка на существование файлов индексации для конкретных полей, но это прямая необходимость
        
        DBFILEPATH = directoryPath + File.separator + name + ".db";

        //　** Будем программно докидывать в начало поле Boolean isDeleted, чтобы делать Soft-Delete **
        // Предполагаем, что ключевое поле будет идти первым


        // Здесь предполагается, что парсер заранее проверил, что более одной колонки и запись не будет пустой
        if (columns.size() == 0) {throw new IllegalArgumentException("Аттрибут столбцов передан пустым");}

        // Смотрим, сколько будет занимать одна запись в БД
        this.columns = new ArrayList<Column>();
        INDEXFILES = new HashMap<String, String>();


        // Начинаем с нуля, т.к у нас isDeleted в начале по сути является полем для определения NULL для ключевого
        int size = 0;
        for (Column item : columns)
        {
            size += item.getSize() + 1;
            this.columns.add(item);
        }

        // Задаем первое поле ключевым
        this.columns.get(0).setKey(true);

        // Один раз перезаписываем final-поле, теперь работаем с ним
        DBRECORDSIZE = size;

        // Здесь делаем проверку на наличие индексных файлов (какие существуют) и загружаем в оперативку
        for (int columnIndex = 0; columnIndex < this.columns.size(); columnIndex++)
        {
            String columnName = columns.get(columnIndex).getName();
            String indexPath = directoryPath + File.separator + name + "_" + columnName + ".idx";
            File currentFile = new File(indexPath);
            if (currentFile.exists())
            {
                // Если поле является ключевым
                if (columns.get(columnIndex).isKey())
                {
                    // Если не удалось сделать индексацию по ключевому полю, то выдаем ошибку
                    if (loadKeyIndex())
                    {
                        this.columns.get(columnIndex).setIndexed(true);
                        INDEXFILES.put(columns.get(columnIndex).getName(), indexPath);
                        KEYFIELDNAME = columnName;
                    }
                    else
                    {
                        System.err.println("Индексация ключевого поля не удалась!");
                        throw new PrimaryKeyIndexException("Не удалось произвести индексацию ключевого поля " + columnName + "!");
                        // Выбросит кастомную ошибку индексации (дописать)
                    }
                }

                // Если поле не является ключевым
                else
                {
                    // Если удалось загрузить индексный файл, то добавляем его в список файлов, иначе отправляем Warning и не индексируем по этому полю
                    if (loadNonKeyIndex()) 
                    {
                        this.columns.get(columnIndex).setIndexed(true);
                        INDEXFILES.put(columns.get(columnIndex).getName(), indexPath);
                    }
                    else
                    {
                        System.err.println("Индексация поля не удалась!");
                    }
                }
            }
        }
    }

    // Загружаем индексные файлы
    // Отдельно загружаем индексный файл для ключевого поля
    public boolean loadKeyIndex() {return true;}
    public boolean loadNonKeyIndex() {return true;}

    // Нужно реализовать: 
    // - вставка строки [сделано]
    // - поиск (по ключевому и по условиям, с индексацией и без)
    // - удаление (в том числе по условиям) -> будем делать сначала getLine(), а потом уже просто заменять 
    // - обновление (аналогично удалению)
    // - индексация по полю (CREATE INDEX)
    // - изменение индексного файла (при вставке, удалении и изменении)

    // Вставка записи
    @SuppressWarnings("unchecked")
    public void insertLine(HashMap<String, Object> values) throws DatabaseOperationException
    {
        // Заранее проверяем крайние случаи, при возникновении которых мы просто не можем выполнять операцию вставки
        if (values.get(KEYFIELDNAME) == null)
        {
            throw new DatabaseOperationException("В запросе не обнаружено ключевого поля " + KEYFIELDNAME + " для данной базы данных!");
        }

        if (((HashMap<Object, Long>)hashes.get(KEYFIELDNAME)).containsKey(values.get(KEYFIELDNAME)))
        {
            throw new DatabaseOperationException("В базе данных уже находится объект с таким значением ключевого аттрибута " + KEYFIELDNAME + "!" +  " Попробуйте применить изменение строки!");
        }

        try (RandomAccessFile rf = new RandomAccessFile(this.DBFILEPATH, "rw");
            RandomAccessFile indexrf = new RandomAccessFile(INDEXFILES.get(KEYFIELDNAME), "rw"))
        {
            Long len = rf.length();
            rf.seek(len);


            for (Column column : this.columns)
            {
                if (column.getType() == Column.DataType.NONE) {continue;}
                String columnName = column.getName();
                
                // Если не нашли такого поля, то делаем маркер на NULL, и заполняем остальные байты мусором (вполне возможно, что пользователь потом отредактирует запись, так что не просто скипаем байты)
                Object searchResult = values.get(columnName);
                if (searchResult == null)
                {
                    rf.writeBoolean(false);
                    rf.write(new byte[column.getSize()]);
                }
                // Если поле найдено, то производим обычную запись
                else
                {
                    // Для ключевого поля дополнительный байт будет играть роль isDeleted (существование всей записи)
                    rf.writeBoolean(true);
                    try 
                    {
                        writeColumn(column, searchResult, rf);
                        // AddToIndex() Сюда передаем indexrf
                    }
                    catch (DatabaseException e) 
                    {
                        //deleteLine() -> удаляем то, что записали, если не получилось записать какие-то данные
                        throw new DatabaseOperationException("Ошибка создания записи! " + e.getMessage());
                    }
                }
            }
        }
        catch (FileNotFoundException e)
        {
            throw new PrimaryKeyIntegrityException("Ошибка при чтении и записи в индексный файл по ключевому полю: " + KEYFIELDNAME + "! " + "Пересоздайте файл .idx для ключевого поля!");
        }
        catch (IOException e)
        {
            throw new DatabaseOperationException(e.getMessage());
        }
        
    }

    // Вспомогательный метод для записи в колонку
    private void writeColumn(Column item, Object value, RandomAccessFile rf) throws DatabaseException
    {
        try
        {
            switch (item.getType())
            {
                case INT:
                    rf.writeInt((Integer)value);
                    break;

                case DOUBLE:
                    rf.writeDouble((Double) value);
                    break;

                case BOOLEAN:
                    rf.writeBoolean((Boolean) value);
                    break;

                case DATE:
                    rf.writeLong((Long) value);
                    break;
                
                case STRING:
                    byte[] stringBytes = ((String) value).getBytes(StandardCharsets.UTF_8);
                    byte[] stringBuffer = new byte[item.getSize()];

                    System.arraycopy(stringBytes, 0, stringBuffer, 0, Math.min(stringBytes.length, item.getSize()));

                    rf.write(stringBuffer);
                    break;

                default: break;
            }
        }
        catch (IOException e)
        {
            throw new DatabaseException("Ошибка при записи в файл! " + e.getLocalizedMessage());
        }

    }


    // Дописать getLine(), который будет возвращать Long для найденных строк, чтобы потом добавить его в search, update, remove
    // Тоже можно перегрузкой сделать, чтобы не сильно менять код


    // Поиск записи по ключевому полю
    // Ключевое поле предполагается целым, а также предполагается, что индексный файл был проиндексирован (за счет конструктора)
    @SuppressWarnings("unchecked")
    public Record searchLine(int value) throws PrimaryKeyIndexException, DatabaseOperationException
    {
        // Переписать под getLine()
        HashMap<Integer, Long> thisMap = (HashMap<Integer, Long>)this.hashes.get(KEYFIELDNAME);
        if (thisMap == null) {throw new PrimaryKeyIndexException("Ошибка поиска! Ключевое поле " + KEYFIELDNAME +  " не проиндексировано!");}
        // Проверка на наличие такого значения
        if (((HashMap<Integer, Long>)this.hashes.get(KEYFIELDNAME)).containsKey(value))
        {
            // Получаем смещение в файле базы данных
            Long offsetValue = thisMap.get(value);
            if (offsetValue == null) {throw new DatabaseOperationException("По ключевому полю " + KEYFIELDNAME + " не найдено записей!");}


            try {return readLine(offsetValue);}
            catch (DatabaseException e) {throw new DatabaseOperationException("Невозможно отобразить записи по ключевому полю " + KEYFIELDNAME + "!");}
            
        }
        else
        {
            throw new DatabaseOperationException("По ключевому полю " + KEYFIELDNAME + " не найдено записей!");
        }
    }

    // Считывает одну строку из файла по предоставленному смещению
    // Ищем смещение -> кидаем в эту функцию
    private Record readLine(Long offset) throws DatabaseException
    {
        try (RandomAccessFile rf = new RandomAccessFile(DBFILEPATH, "r"))
        {
            rf.seek(offset);
            Boolean isDeleted = rf.readBoolean();
            if (isDeleted == true) {throw new DatabaseException("Не удалось считать строчку по смещению. Строка была удалена.");}
            HashMap<String, Object> values = new HashMap<>();
            for (Column column : this.columns)
            {
                if (!column.isKey() && !rf.readBoolean()) {continue;}
                String columnName = column.getName();
                switch (column.getType())
                {
                    case INT:
                        values.put(columnName, rf.readInt());
                        break;

                    case DOUBLE:
                        values.put(columnName, rf.readDouble());
                        break;

                    case BOOLEAN:
                        values.put(columnName, rf.readBoolean());
                        break;

                    case DATE:
                        values.put(columnName, rf.readLong());
                        break;
                    
                    case STRING:
                        byte[] nameBuffer = new byte[column.getSize()];
                        rf.readFully(nameBuffer);
                        String name = new String(nameBuffer, StandardCharsets.UTF_8).trim();
                        values.put(columnName, name);
                        break;

                    default: break;
                    }
            }
            return new Record(values);
        }
        catch (IOException e)
        {
            throw new DatabaseException("Не удалось считать строчку по смещению. Системная ошибка.");
        }
    }

    





}