package com.weibo.dip.scheduler.example;

/**
 * Application Exception.
 *
 * @author yurun
 */
public class AppException {
  /**
   * Main.
   *
   * @param args no params
   */
  public static void main(String[] args) {
    try {
      throw new Exception("Application exception example");
    } catch (Exception e) {
      System.out.println(e.getMessage());

      System.exit(-1);
    }
  }
}
