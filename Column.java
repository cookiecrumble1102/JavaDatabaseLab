

public class Column implements java.io.Serializable
{
    private static final long serialVersionUID = 1L;

    // За счет энума потом будет проще делать сравнения по типу
    public enum DataType
    {
        INT(4),
        DOUBLE(8),
        BOOLEAN(1),
        DATE(8),
        STRING(200), // Сделали строку в 200 символов на случай, если пользователь не уточнил размер (в случае с предметной областью 200 символов хватает за глаза)
        NONE(0);
        
        private int size;

        DataType(int size)
        {
            this.size = size;
        }

        public int getSize() {return this.size;}
        public void setSize(int size) {this.size = size;}
    }
    
    private String name;
    private DataType type;
    private int size;
    private boolean isIndexed = false;
    private boolean isKey = false;
    private String referencedTable;
    private String referencedColumn;

    public void setReference(String tableName, String columnName)
    {
        this.referencedTable = tableName;
        this.referencedColumn = columnName;
    }

    public String getReferencedTable()
    {
        return referencedTable;
    }

    public String getReferencedColumn()
    {
        return referencedColumn;
    }

    // Конструктор по имени типа (все кроме STRING)
    public Column(String columnName, String typeName) throws IllegalArgumentException
    {
        this.name = columnName;
        try
        {
            this.type = getTypeFromString(typeName);
        }
        catch (IllegalArgumentException e)
        {
            this.type = DataType.NONE;
            throw e;
        }
        this.size = type.getSize();
    }

    // Конструктор по имени типа и размеру (STRING)
    public Column (String columnName, String typeName, int size) throws IllegalArgumentException
    {
        this.name = columnName;
        try
        {
            this.type = getTypeFromString(typeName);
            if (this.type != DataType.STRING)
            {
                throw new IllegalArgumentException("Невозможно задать точный размер для типа данных - " + typeName); 
            }
        }
        catch (IllegalArgumentException e)
        {
            this.type = DataType.NONE;
            throw e;
        }

        if (size <= 0)
        {
            throw new IllegalArgumentException("Невозможно задать колонку с размером равным или меньше 0"); 
        }
        this.size = size;
    }


    public static DataType getTypeFromString(String typeName)
    {
            switch (typeName.toUpperCase())
            {
                case "INTEGER":
                case "INT": return DataType.INT;
                
                case "FLOAT":
                case "DOUBLE": return DataType.DOUBLE;

                case "BOOLEAN": return DataType.BOOLEAN;
                case "DATE": return DataType.DATE;

                case "STRING":
                    return DataType.STRING;
                
                // Перебрасывает ошибку в конструктор
                default: throw new IllegalArgumentException("Некорректный тип данных - " + typeName);
            }
    }

    // Геттеры
    public String getName() {return this.name;}
    public DataType getType() {return this.type;}
    public int getSize() {return this.size;}
    public boolean isKey() {return this.isKey;}
    public boolean isIndexed() {return this.isIndexed;}

    // Сеттеры
    public void setIndexed(boolean boolValue) {this.isIndexed = boolValue;}
    public void setKey(boolean boolValue) {this.isKey = boolValue;}

}
